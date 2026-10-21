/*
 * 校园网保活守护（C 版）。打包在应用的 lib/<abi>/libguardd.so 里（其实是可执行文件），
 * 由数据目录里的 start.sh 用 root 或 ADB 启动：libguardd.so <数据目录>。
 * 不属于应用进程，应用被杀、被强行停止都不影响它；只依赖 libc，常驻内存很小。
 *
 * 平时睡在 poll() 里，只在这几种时候醒：检测时间到了；内核通知网卡、IP 变了（netlink）；每 10 秒看一眼控制区。
 * 检测都在进程里做：ICMP 数据报套接字 ping 门户，自己发 HTTP 请求查在线状态，不起子进程。
 * 读 Wi-Fi 名称、重连 Wi-Fi（没有公开的 C 接口，用 cmd wifi）和拉起应用（am）才调系统命令，而且只在状态变化时。
 *
 * 和应用之间只有一个约 2 KB 的二进制文件 guard.bin（协议见 Guard.kt，两边要一致），守护不写任何文字：
 *   头部 64 字节：应用写控制区（检测间隔、停止 / 暂停 / 通知标志、立即检测请求、门户 IP 和端口、应用进程号），
 *   守护写状态区（pid、启动时间、身份、最近一次检测的时间、状态、ping）；后面是 RING 条 8 字节的事件记录
 *   （时间、事件、参数），环形覆盖。
 *   应用读这个文件，自己把事件翻成日志、把状态渲染到界面上。
 * 另有一个文本文件 ssid：记住的校园网（“是否开放网络 名称”），只在换了校园网时写。
 *
 * 规则：
 *  1. 记住能连到门户的那个 Wi-Fi（校园网）。它断开超过 15 秒还没被系统连回去，就扫描，是开放网络就直接重连；
 *     Wi-Fi 被手动关掉、或者连的是别的 Wi-Fi（比如回家了）就不管。
 *  2. 连着 Wi-Fi 时每隔 interval 秒 ping 一次门户、查一次在线状态（套接字绑在 Wi-Fi 上，不受移动数据影响）。
 *  3. 门户说不在线、应用又没暂停自动重连时，拉起应用的保活服务去登录（账号密码只在应用里加密保存），
 *     1 分钟内只叫一次；登录完应用自己退出。
 *  4. 连着校园网但连续 3 次连不上门户，就重连一次 Wi-Fi。
 *  5. 控制区里的 pid 不是自己（新启动的守护接替了）、或者应用设了停止标志，就退出。
 *  6. 用户开了“守护通知”：状态变了就拉起应用更新通知（应用更新完自己退出）。应用进程没清进程号就不在了
 *     （被强行停止，比如 ColorOS 划掉后台），系统会把通知一起清掉，这时也拉起应用重新显示。
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <linux/fib_rules.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <netinet/in.h>
#include <netinet/ip_icmp.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define PKG "io.github.justlikecheese.xiaoyuan"
#define IFACE "wlan0"
#define TICK_MS 10000          /* 每 10 秒看一眼控制区 */
#define RECONNECT_AFTER 15     /* 校园网断开多少秒后自己去连 */
#define KICK_EVERY 30          /* 重连 Wi-Fi 最短间隔 */
#define LOGIN_EVERY 60         /* 叫应用登录最短间隔 */
#define NOTIFY_EVERY 3         /* 拉起应用更新通知最短间隔 */

/* ---------------------------------------------------------------- 协议（和 Guard.kt 一致） */

#define MAGIC 0x31475958u      /* "XYG1" */
#define VERSION 4              /* 守护程序的版本，改了守护就加一：应用发现在跑的是旧版就换成新的（和 Guard.kt 一致） */
#define RING 256

#define FLAG_STOP 1
#define FLAG_PAUSED 2
#define FLAG_NOTIFY 4          /* 用户开了“守护通知” */

enum { ST_CHECKING, ST_ONLINE, ST_OFFLINE, ST_UNREACHABLE, ST_WIFI_DOWN, ST_WIFI_OFF, ST_OTHER };

enum {
    EV_START = 1,       /* 参数：0 root，1 shell */
    EV_EXIT,            /* 参数：0 正常退出，1 被新守护接替 */
    EV_ONLINE,          /* 参数：ping（0.1 毫秒），0 超时 */
    EV_OFFLINE,
    EV_UNREACHABLE,     /* 参数：ping */
    EV_WIFI_DOWN,
    EV_WIFI_UP,         /* 参数：断开了多少秒 */
    EV_WIFI_OFF,
    EV_OTHER_NET,
    EV_RECONNECT_WIFI,
    EV_CALL_APP,
    EV_FAILS_RECONNECT, /* 连续 3 次连不上门户，重连 Wi-Fi */
};

struct header {
    uint32_t magic;
    /* 应用写 */
    uint16_t interval;   /* 检测间隔（秒） */
    uint8_t flags;       /* FLAG_* */
    uint8_t check;       /* 立即检测：应用每请求一次加一 */
    uint32_t portal_ip;  /* 门户 IPv4，网络字节序 */
    uint16_t portal_port;
    uint16_t reserved1;
    /* 守护写 */
    int32_t pid;         /* 当前的守护 */
    uint32_t started;    /* 启动时间（秒） */
    uint32_t uid;        /* 0 root，2000 shell */
    uint32_t at;         /* 最近一次检测的时间（秒） */
    uint8_t state;       /* ST_* */
    uint8_t reserved2;
    uint16_t ping;       /* 门户延迟（0.1 毫秒），0 超时 */
    uint16_t next;       /* 下一条记录写在第几格 */
    uint16_t reserved3;
    uint32_t pss_kb;     /* 守护自己占的内存（PSS，KB），应用最小化时提示用 */
    uint32_t version;    /* 守护程序的版本 */
    /* 应用写 */
    int32_t app_pid;     /* 应用进程号：进程起来时写，自己退出前清 0 */
    uint8_t reserved4[12];
};

struct record {
    uint32_t time;
    uint8_t event;
    uint8_t reserved;
    uint16_t arg;
};

_Static_assert(sizeof(struct header) == 64, "header");
_Static_assert(sizeof(struct record) == 8, "record");

/* ---------------------------------------------------------------- 状态 */

static char dir[512];
static char bin_path[600];
static int is_root;
static volatile sig_atomic_t quit;

static struct header ctl;  /* 最近读到的头部（只用控制区） */
static uint16_t next_slot;

static char campus[256];   /* 记住的校园网 */
static int campus_open;
static char cur[256];      /* 现在连的 Wi-Fi，空串表示没连 */
static int cur_open;
static char last_cur[256]; /* 断开前连的 */
static unsigned net_id;    /* Wi-Fi 的网络编号（netd 用的 netId），0 表示不知道 */
static int cur_state = -1; /* 最近一次检测的结果 ST_*，还没检测过为 -1 */
static int shown_state = -1;   /* 通知上显示的状态，-1 表示要重新显示 */
static int32_t gone_pid;   /* 已经发现不在了的应用进程（同一个只处理一次） */
static time_t last_notify;
static uint8_t check_seen; /* 处理过的立即检测请求 */

static time_t mono(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_BOOTTIME, &ts);   /* 含休眠时间 */
    return ts.tv_sec;
}

/* ---------------------------------------------------------------- guard.bin 读写 */

/*
 * 每次都重新打开：数据目录在外部存储（FUSE）上，应用那边是直接写底层文件的，长开着的文件可能读到旧缓存；
 * 关文件时 FUSE 会把写入落到底层，应用马上就能读到。每 10 秒一次 open + pread + close，开销可以忽略。
 */
static void put(size_t off, const void *data, size_t n)
{
    int fd = open(bin_path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return;
    (void) !pwrite(fd, data, n, (off_t) off);
    close(fd);
}

static int read_header(struct header *h)
{
    int fd = open(bin_path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t r = pread(fd, h, sizeof *h, 0);
    close(fd);
    return r == (ssize_t) sizeof *h && h->magic == MAGIC ? 0 : -1;
}

static void event(int ev, unsigned arg)
{
    struct record r = {(uint32_t) time(NULL), (uint8_t) ev, 0, (uint16_t) (arg > 0xffff ? 0xffff : arg)};
    put(sizeof(struct header) + next_slot * sizeof r, &r, sizeof r);
    next_slot = (uint16_t) ((next_slot + 1) % RING);
    put(offsetof(struct header, next), &next_slot, sizeof next_slot);
}

/* 自己占的内存（PSS，KB）：读 /proc/self/smaps_rollup 的 Pss 行，每次检测读一次，开销可以忽略。 */
static uint32_t self_pss_kb(void)
{
    char b[1024];
    int fd = open("/proc/self/smaps_rollup", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t r = read(fd, b, sizeof b - 1);
    close(fd);
    if (r <= 0) return 0;
    b[r] = 0;
    char *p = strstr(b, "\nPss:");
    return p ? (uint32_t) strtoul(p + 5, NULL, 10) : 0;
}

static void status(int state, unsigned ping)
{
    uint32_t pss = self_pss_kb();
    put(offsetof(struct header, pss_kb), &pss, sizeof pss);
    struct {
        uint32_t at;
        uint8_t state, reserved;
        uint16_t ping;
    } s = {(uint32_t) time(NULL), (uint8_t) state, 0, (uint16_t) (ping > 0xffff ? 0xffff : ping)};
    put(offsetof(struct header, at), &s, sizeof s);
    cur_state = state;
}

/* 还该不该继续跑：应用设了停止标志，或者 pid 已经不是自己（新启动的守护接替了，比如换了保活方式），就退出。 */
static int should_run(void)
{
    if (quit) return 0;
    if (read_header(&ctl) < 0) return 1;   /* 读不到（应用正在重写）就先继续 */
    return !(ctl.flags & FLAG_STOP) && ctl.pid == getpid();
}

static int interval(void)
{
    int v = ctl.interval;
    return v < 10 ? 10 : v > 600 ? 600 : v;
}

/* ---------------------------------------------------------------- 记住的校园网 */

static void load_campus(void)
{
    char p[600], b[300];
    snprintf(p, sizeof p, "%s/ssid", dir);
    int fd = open(p, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    ssize_t r = read(fd, b, sizeof b - 1);
    close(fd);
    if (r < 3 || (b[0] != '0' && b[0] != '1') || b[1] != ' ') return;
    b[r] = 0;
    b[strcspn(b, "\n")] = 0;
    campus_open = b[0] == '1';
    snprintf(campus, sizeof campus, "%s", b + 2);
}

static void save_campus(void)
{
    char p[600], line[300];
    snprintf(p, sizeof p, "%s/ssid", dir);
    int n = snprintf(line, sizeof line, "%d %s\n", campus_open, campus);
    int fd = open(p, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return;
    (void) !write(fd, line, (size_t) n);
    close(fd);
}

/* ---------------------------------------------------------------- 系统命令 */

/* 执行系统命令，把输出（最多 n-1 字节）放进 out（可为 NULL）；超过 timeout_s 秒就结束它。返回退出码，失败 -1。 */
static int run(char *const argv[], char *out, size_t n, int timeout_s)
{
    int pfd[2];
    if (pipe2(pfd, O_CLOEXEC) < 0) return -1;
    pid_t pid = fork();
    if (pid < 0) {
        close(pfd[0]);
        close(pfd[1]);
        return -1;
    }
    if (pid == 0) {
        dup2(pfd[1], 1);
        dup2(pfd[1], 2);
        int null = open("/dev/null", O_RDONLY);
        if (null >= 0) dup2(null, 0);
        execv(argv[0], argv);
        _exit(127);
    }
    close(pfd[1]);
    size_t len = 0;
    time_t deadline = mono() + timeout_s;
    for (;;) {
        time_t left = deadline - mono();
        if (left <= 0) {
            kill(pid, SIGKILL);
            break;
        }
        struct pollfd p = {pfd[0], POLLIN, 0};
        int r = poll(&p, 1, (int) left * 1000);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) {
            kill(pid, SIGKILL);
            break;
        }
        char buf[2048];
        ssize_t k = read(pfd[0], buf, sizeof buf);
        if (k <= 0) break;
        if (out && len + 1 < n) {
            size_t c = (size_t) k < n - 1 - len ? (size_t) k : n - 1 - len;
            memcpy(out + len, buf, c);
            len += c;
        }
    }
    if (out) out[len] = 0;
    close(pfd[0]);
    int st = 0;
    while (waitpid(pid, &st, 0) < 0 && errno == EINTR) {}
    return WIFEXITED(st) ? WEXITSTATUS(st) : -1;
}

/* Wi-Fi 状态：返回 0 已关闭、1 开着；连着的话 cur / cur_open 写成它的名称、是否开放网络，没连 cur 为空串。 */
static int wifi_status(void)
{
    static char out[4096];   /* 只看开头几行 */
    char *argv[] = {"/system/bin/cmd", "wifi", "status", NULL};
    cur[0] = 0;
    cur_open = 0;
    if (run(argv, out, sizeof out, 10) < 0) return 1;
    if (strstr(out, "Wifi is disabled")) return 0;
    const char *key = "Wifi is connected to \"";
    char *s = strstr(out, key);
    if (s) {
        s += strlen(key);
        char *e = strchr(s, '"');
        if (e && (size_t) (e - s) < sizeof cur) {
            memcpy(cur, s, (size_t) (e - s));
            cur[e - s] = 0;
        }
        cur_open = strstr(out, "Security type: 0,") != NULL;
    }
    return 1;
}

static void wifi_connect(const char *ssid)
{
    char *scan[] = {"/system/bin/cmd", "wifi", "start-scan", NULL};
    run(scan, NULL, 0, 10);
    if (!campus_open) return;   /* 加密网络要密码，只能等系统自己连 */
    char *argv[] = {"/system/bin/cmd", "wifi", "connect-network", (char *) ssid, "open", NULL};
    run(argv, NULL, 0, 15);
}

/* 拉起应用的保活服务去检测、登录（服务只对 root / shell 开放）。 */
static void call_app(void)
{
    char *argv[] = {"/system/bin/am", "start-foreground-service", "-n", PKG "/.service.KeepAliveService",
                    "-a", PKG ".GUARD_CHECK", NULL};
    run(argv, NULL, 0, 20);
}

/*
 * “守护通知”：状态和通知上的不一样就拉起应用去更新（应用读 guard.bin 自己写通知，更新完退出），
 * 3 秒内最多一次（来不及的下一轮再更新）。应用进程不在了却没清进程号，说明是被强行停止的，通知也被系统清掉了，重新显示。
 */
static void notify_app(void)
{
    if (!(ctl.flags & FLAG_NOTIFY)) {
        shown_state = -1;
        return;
    }
    if (ctl.app_pid > 0 && ctl.app_pid != gone_pid && kill(ctl.app_pid, 0) < 0 && errno == ESRCH) {
        gone_pid = ctl.app_pid;
        shown_state = -1;
    }
    if (cur_state < 0 || cur_state == shown_state) return;
    time_t now = mono();
    if (last_notify && now - last_notify < NOTIFY_EVERY) return;
    last_notify = now;
    shown_state = cur_state;
    char *argv[] = {"/system/bin/am", "start-foreground-service", "-n", PKG "/.service.GuardNotifyService", NULL};
    run(argv, NULL, 0, 20);
}

/* ---------------------------------------------------------------- 网络 */

/* wlan0 有没有 IPv4 地址（有就是连上了 Wi-Fi）。 */
static int wifi_has_ip(struct in_addr *addr)
{
    struct ifaddrs *list, *i;
    int found = 0;
    if (getifaddrs(&list) < 0) return 0;
    for (i = list; i; i = i->ifa_next) {
        if (i->ifa_addr && i->ifa_addr->sa_family == AF_INET && strcmp(i->ifa_name, IFACE) == 0) {
            if (addr) *addr = ((struct sockaddr_in *) i->ifa_addr)->sin_addr;
            found = 1;
            break;
        }
    }
    freeifaddrs(list);
    return found;
}

/*
 * 从路由规则里找 Wi-Fi 的网络编号：先找“oif wlan0 lookup 表”的表号，再找同一张表上
 * “fwmark 0x1xxxx/0x1ffff”（显式选择网络的规则），低 16 位就是 netId。和 ip rule 读的是同一份，不用起子进程。
 */
static unsigned find_net_id(void)
{
    int s = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);
    if (s < 0) return 0;
    struct {
        struct nlmsghdr h;
        struct fib_rule_hdr r;
    } req = {
        .h = {.nlmsg_len = sizeof req, .nlmsg_type = RTM_GETRULE, .nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP, .nlmsg_seq = 1},
        .r = {.family = AF_INET},
    };
    if (send(s, &req, sizeof req, 0) < 0) {
        close(s);
        return 0;
    }
    struct rule {
        unsigned table, mark, mask;
        int wifi_oif;
    } rules[256];
    int count = 0, done = 0;
    static char buf[16384];
    while (!done) {
        int len = (int) recv(s, buf, sizeof buf, 0);
        if (len <= 0) break;
        for (struct nlmsghdr *h = (struct nlmsghdr *) buf; NLMSG_OK(h, len); h = NLMSG_NEXT(h, len)) {
            if (h->nlmsg_type == NLMSG_DONE || h->nlmsg_type == NLMSG_ERROR) {
                done = 1;
                break;
            }
            if (h->nlmsg_type != RTM_NEWRULE || count >= 256) continue;
            struct fib_rule_hdr *fr = NLMSG_DATA(h);
            struct rule r = {fr->table, 0, 0, 0};
            int alen = (int) (h->nlmsg_len - NLMSG_LENGTH(sizeof *fr));
            for (struct rtattr *a = (struct rtattr *) ((char *) fr + NLMSG_ALIGN(sizeof *fr)); RTA_OK(a, alen);
                 a = RTA_NEXT(a, alen)) {
                switch (a->rta_type) {
                case FRA_TABLE: r.table = *(unsigned *) RTA_DATA(a); break;
                case FRA_FWMARK: r.mark = *(unsigned *) RTA_DATA(a); break;
                case FRA_FWMASK: r.mask = *(unsigned *) RTA_DATA(a); break;
                case FRA_OIFNAME: r.wifi_oif = strcmp((char *) RTA_DATA(a), IFACE) == 0; break;
                }
            }
            rules[count++] = r;
        }
    }
    close(s);
    unsigned table = 0, id = 0;
    for (int i = 0; i < count && !table; i++)
        if (rules[i].wifi_oif) table = rules[i].table;
    for (int i = 0; i < count && table && !id; i++)
        if (rules[i].table == table && rules[i].mask == 0x1ffff && (rules[i].mark & 0x10000)) id = rules[i].mark & 0xffff;
    return id;
}

/*
 * 让套接字走 netId 这个网络（和应用里 Network.bindSocket、NDK 的 android_setsocknetwork 一样）。
 * root 直接打上 netd 的标记（第 16 位“显式选择”+ netId，路由规则按它选表）；shell 调 libnetd_client 的
 * setNetworkForSocket（libc 联网本来就用这个库，加载它几乎不占内存；不链接 libandroid，那会多出十几 MB）。
 */
static int select_network(int s, unsigned id)
{
    if (is_root) {
        unsigned mark = 0x10000 | id;
        if (setsockopt(s, SOL_SOCKET, SO_MARK, &mark, sizeof mark) == 0) return 0;
    }
    static int (*set_net)(unsigned, int);
    static int tried;
    if (!tried) {
        tried = 1;
        void *h = dlopen("libnetd_client.so", RTLD_NOW);
        if (h) set_net = (int (*)(unsigned, int)) dlsym(h, "setNetworkForSocket");
    }
    return set_net ? set_net(id, s) : -1;
}

/* 把套接字绑到 Wi-Fi 上：知道 netId 就选这个网络，不知道时 root 直接绑网卡，shell 绑本机地址。 */
static void bind_wifi(int s, const struct in_addr *local)
{
    if (net_id && select_network(s, net_id) == 0) return;
    if (is_root && setsockopt(s, SOL_SOCKET, SO_BINDTODEVICE, IFACE, sizeof IFACE) == 0) return;
    if (local) {
        struct sockaddr_in a = {.sin_family = AF_INET, .sin_addr = *local};
        bind(s, (struct sockaddr *) &a, sizeof a);
    }
}

static long elapsed_us(const struct timespec *a)
{
    struct timespec b;
    clock_gettime(CLOCK_MONOTONIC, &b);
    return (b.tv_sec - a->tv_sec) * 1000000L + (b.tv_nsec - a->tv_nsec) / 1000;
}

/* ping 门户一次（ICMP 数据报套接字，不用 root），返回延迟（0.1 毫秒），超时返回 0。 */
static unsigned ping_portal(const struct sockaddr_in *dst, const struct in_addr *local)
{
    unsigned result = 0;
    int s = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, IPPROTO_ICMP);
    if (s < 0) return 0;
    bind_wifi(s, local);
    struct icmphdr req = {.type = ICMP_ECHO};
    req.un.echo.sequence = htons(1);
    struct sockaddr_in to = *dst;
    to.sin_port = 0;
    struct timespec start;
    clock_gettime(CLOCK_MONOTONIC, &start);
    if (sendto(s, &req, sizeof req, 0, (struct sockaddr *) &to, sizeof to) == (ssize_t) sizeof req) {
        for (;;) {
            long left = 3000 - elapsed_us(&start) / 1000;
            struct pollfd p = {s, POLLIN, 0};
            if (left <= 0 || poll(&p, 1, (int) left) <= 0) break;
            struct icmphdr rep;
            if (recv(s, &rep, sizeof rep, 0) >= (ssize_t) sizeof rep && rep.type == ICMP_ECHOREPLY) {
                long tenths = elapsed_us(&start) / 100;
                result = tenths < 1 ? 1 : (unsigned) tenths;
                break;
            }
        }
    }
    close(s);
    return result;
}

/* 查在线状态（门户的 chkstatus，和网页、应用用的是同一个接口）：1 在线，0 不在线，-1 连不上。 */
static int check_portal(const struct sockaddr_in *dst, const struct in_addr *local)
{
    int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (s < 0) return -1;
    bind_wifi(s, local);
    int result = -1;
    struct pollfd p = {s, POLLOUT, 0};
    if (connect(s, (const struct sockaddr *) dst, sizeof *dst) == 0 ||
        (errno == EINPROGRESS && poll(&p, 1, 5000) == 1)) {
        int err = 0;
        socklen_t el = sizeof err;
        getsockopt(s, SOL_SOCKET, SO_ERROR, &err, &el);
        if (err == 0) {
            char host[24], req[256];
            inet_ntop(AF_INET, &dst->sin_addr, host, sizeof host);
            int k = snprintf(req, sizeof req,
                             "GET /drcom/chkstatus?callback=dr1002&jsVersion=4.X&v=%ld&lang=zh HTTP/1.0\r\n"
                             "Host: %s\r\nConnection: close\r\n\r\n",
                             (long) time(NULL) % 10000, host);
            if (send(s, req, (size_t) k, MSG_NOSIGNAL) == k) {
                static char resp[8192];
                size_t len = 0;
                struct timespec start;
                clock_gettime(CLOCK_MONOTONIC, &start);
                for (;;) {
                    long left = 6000 - elapsed_us(&start) / 1000;
                    struct pollfd q = {s, POLLIN, 0};
                    if (left <= 0 || poll(&q, 1, (int) left) <= 0) break;
                    ssize_t r = recv(s, resp + len, sizeof resp - 1 - len, 0);
                    if (r <= 0) break;
                    len += (size_t) r;
                    resp[len] = 0;
                    if (strstr(resp, "\"result\":1")) {
                        result = 1;
                        break;
                    }
                    if (strstr(resp, "\"result\":0")) {
                        result = 0;
                        break;
                    }
                    if (len + 1 >= sizeof resp) break;
                }
            }
        }
    }
    close(s);
    return result;
}

/* ---------------------------------------------------------------- 等待 */

static int open_netlink(void)
{
    int s = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC | SOCK_NONBLOCK, NETLINK_ROUTE);
    if (s < 0) return -1;
    struct sockaddr_nl a = {.nl_family = AF_NETLINK, .nl_groups = RTMGRP_LINK | RTMGRP_IPV4_IFADDR};
    if (bind(s, (struct sockaddr *) &a, sizeof a) < 0) {
        close(s);
        return -1;
    }
    return s;
}

/*
 * 睡 seconds 秒；wlan0 有没有 IP 变了（netlink 通知）、应用请求立即检测就提前醒，返回 1；
 * 每 10 秒看一眼控制区（顺便看通知要不要更新）。
 * 订阅不了 netlink 时（极少见）退回每 5 秒看一眼 wlan0 有没有 IP。
 */
static int wait_for(int nl, int seconds)
{
    time_t deadline = mono() + seconds;
    int had_ip = wifi_has_ip(NULL);
    while (should_run()) {
        notify_app();
        if (ctl.check != check_seen) {
            check_seen = ctl.check;
            return 1;
        }
        time_t left = deadline - mono();
        if (left <= 0) return 0;
        int ms = left * 1000 < TICK_MS ? (int) left * 1000 : TICK_MS;
        if (nl < 0) {
            if (ms > 5000) ms = 5000;
            poll(NULL, 0, ms);
            if (wifi_has_ip(NULL) != had_ip) return 1;
            continue;
        }
        struct pollfd p = {nl, POLLIN, 0};
        if (poll(&p, 1, ms) > 0) {
            static char buf[8192];
            int changed = 0, len;
            while ((len = (int) recv(nl, buf, sizeof buf, 0)) > 0)
                for (struct nlmsghdr *h = (struct nlmsghdr *) buf; NLMSG_OK(h, len); h = NLMSG_NEXT(h, len))
                    if (h->nlmsg_type == RTM_NEWADDR || h->nlmsg_type == RTM_DELADDR || h->nlmsg_type == RTM_NEWLINK ||
                        h->nlmsg_type == RTM_DELLINK)
                        changed = 1;
            /* 只关心 wlan0 有没有 IPv4 这件事变没变，别的网卡（移动数据）变化不用醒 */
            if (changed && wifi_has_ip(NULL) != had_ip) return 1;
        }
    }
    return 0;
}

/* ---------------------------------------------------------------- 主循环 */

static void on_signal(int sig)
{
    (void) sig;
    quit = 1;
}

int main(int argc, char **argv)
{
    if (argc < 2 || strlen(argv[1]) >= sizeof dir) return 2;
    strcpy(dir, argv[1]);
    snprintf(bin_path, sizeof bin_path, "%s/guard.bin", dir);
    if (read_header(&ctl) < 0) return 1;   /* 应用还没写好控制区 */
    check_seen = ctl.check;
    is_root = getuid() == 0;

    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    signal(SIGHUP, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);

    /* 宣布自己是当前的守护（旧的下次看控制区时发现 pid 不是自己，就会退出） */
    struct {
        int32_t pid;
        uint32_t started, uid;
    } me = {getpid(), (uint32_t) time(NULL), (uint32_t) getuid()};
    put(offsetof(struct header, pid), &me, sizeof me);
    uint32_t version = VERSION;
    put(offsetof(struct header, version), &version, sizeof version);
    next_slot = ctl.next % RING;
    if (is_root) {   /* 内存紧张时别先杀它（-1000 留给系统进程）；shell 身份没权限调低，保持 adb 给的值 */
        int fd = open("/proc/self/oom_score_adj", O_WRONLY | O_CLOEXEC);
        if (fd >= 0) {
            (void) !write(fd, "-800", 4);
            close(fd);
        }
    }
    load_campus();

    int nl = open_netlink();
    int state = -1;
    time_t down_since = 0, last_kick = 0, last_login = 0;
    int fails = 0, need_info = 1, wifi_on = 1;
    int wifi_issue = 0, net_issue = 0;   /* 记过 Wi-Fi 断开 / 掉线、连不上：解决了再记一条“恢复” */

    while (should_run()) {
        time_t now = mono();
        struct in_addr local;
        int connected = wifi_has_ip(&local);

        if (!connected) {
            if (!down_since) {
                down_since = now;
                snprintf(last_cur, sizeof last_cur, "%s", cur);
                net_id = 0;
                need_info = 1;
                wifi_on = wifi_status();
                if (wifi_on) {   /* 手动关掉的不算问题，不记 */
                    event(EV_WIFI_DOWN, 0);
                    wifi_issue = 1;
                }
            }
            /* 断开前连的是校园网、过了 15 秒系统还没连回去：扫描，开放网络就直接连（30 秒最多一次） */
            if (now - down_since >= RECONNECT_AFTER && campus[0] && strcmp(last_cur, campus) == 0 && now - last_kick >= KICK_EVERY) {
                last_kick = now;
                wifi_on = wifi_status();
                if (wifi_on) {
                    event(EV_RECONNECT_WIFI, 0);
                    wifi_connect(campus);
                }
            }
            state = wifi_on ? ST_WIFI_DOWN : ST_WIFI_OFF;
            status(state, 0);
            wait_for(nl, wifi_on ? 5 : interval());
            continue;
        }
        if (down_since) {
            if (wifi_issue) event(EV_WIFI_UP, (unsigned) (now - down_since));
            wifi_issue = 0;
            down_since = 0;
            need_info = 1;
        }
        if (need_info) {   /* 刚连上：读一次 Wi-Fi 名称和网络编号 */
            wifi_status();
            net_id = find_net_id();
            need_info = 0;
        }

        struct sockaddr_in dst = {.sin_family = AF_INET, .sin_port = htons(ctl.portal_port ? ctl.portal_port : 80)};
        dst.sin_addr.s_addr = ctl.portal_ip;
        unsigned ping = 0;
        int r = -1;
        if (dst.sin_addr.s_addr) {
            ping = ping_portal(&dst, &local);
            r = check_portal(&dst, &local);
            if (r < 0) {   /* 可能是网络编号变了（重连过），重新找一次再试 */
                unsigned id = find_net_id();
                if (id != net_id) {
                    net_id = id;
                    r = check_portal(&dst, &local);
                }
            }
        }
        if (r >= 0 && cur[0] && (strcmp(campus, cur) != 0 || campus_open != cur_open)) {
            /* 门户有回复：这个 Wi-Fi 就是校园网，记下来（断开时只重连它） */
            snprintf(campus, sizeof campus, "%s", cur);
            campus_open = cur_open;
            save_campus();
        }
        if (r == 1) {
            fails = 0;
            /* 只记问题解决（记过不在线、连不上之后恢复）；一直在线、刚启动都不记 */
            if (net_issue) event(EV_ONLINE, ping);
            net_issue = 0;
            state = ST_ONLINE;
        } else if (r == 0) {
            fails = 0;
            if (state != ST_OFFLINE && !(ctl.flags & FLAG_PAUSED)) {   /* 手动注销（暂停）后的不在线不算问题 */
                event(EV_OFFLINE, 0);
                net_issue = 1;
            }
            state = ST_OFFLINE;
            if (!(ctl.flags & FLAG_PAUSED) && now - last_login >= LOGIN_EVERY) {
                last_login = now;
                event(EV_CALL_APP, 0);
                call_app();
            }
        } else if (cur[0] && strcmp(cur, campus) != 0) {
            state = ST_OTHER;   /* 连的不是校园网：不算问题，不记 */
        } else {
            fails++;
            if (state != ST_UNREACHABLE) {
                event(EV_UNREACHABLE, ping);
                net_issue = 1;
            }
            state = ST_UNREACHABLE;
            if (fails >= 3 && campus[0] && campus_open) {
                fails = 0;
                event(EV_FAILS_RECONNECT, 0);
                wifi_connect(campus);
                need_info = 1;
            }
        }
        status(state, ping);
        if (wait_for(nl, interval())) need_info = 1;   /* 网卡有变化：马上再检测一轮 */
    }

    int owner = ctl.pid == getpid();
    if (owner) {   /* 自己是当前的守护：清掉守护信息，应用据此知道没有守护在跑 */
        struct {
            int32_t pid;
            uint32_t started, uid, at;
        } none = {0, 0, 0, 0};
        put(offsetof(struct header, pid), &none, sizeof none);
    }
    if (nl >= 0) close(nl);
    return 0;
}
