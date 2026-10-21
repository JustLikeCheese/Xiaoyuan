package io.github.justlikecheese.xiaoyuan.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.justlikecheese.xiaoyuan.portal.UrlTransport
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URL

/**
 * 盯着 Wi-Fi：记下当前的 Wi-Fi 网络，门户请求都从它发出；Wi-Fi 连上、断开、被门户拦截、恢复上网时通知 Backend
 * （作用同桌面端的 QNetworkInformation）。
 *
 * 校园网登录前系统判定 Wi-Fi “无法访问互联网”，开着移动数据时默认网络是流量，不绑定的话请求会走 4G/5G 到不了门户。
 */
class NetworkWatcher(context: Context) {
    interface Listener {
        /** state：断开 / 仅内网 / 可上网 */
        fun onReachability(state: String)
        fun onCaptivePortal(behind: Boolean)
    }

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val flags = HashMap<Network, Pair<Boolean, Boolean>>()   // 每个网络上次的（已验证可上网, 被门户拦截）
    private var startedAt = 0L

    @Volatile
    var wifi: Network? = null
        private set
    var listener: Listener? = null

    /** Wi-Fi 上本机的 IPv4 地址（内网 IP），没连 Wi-Fi 时为空串。 */
    val localIp = MutableStateFlow("")

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifi = network
            localIp.value = wifiIpv4()
            // 注册时系统会把已连着的网络立刻报一遍，那不算变化（启动时本来就会查一次状态）
            if (SystemClock.elapsedRealtime() - startedAt > 1500) listener?.onReachability("Wi-Fi 已连接")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) to
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            val before = flags.put(network, now) ?: return   // 第一次见到这个网络：只记下来
            if (now == before) return   // 信号强度之类的变化也会回调，只关心这两项
            if (now.second && !before.second) listener?.onCaptivePortal(true)
            else if (now.first != before.first) listener?.onReachability(if (now.first) "可上网" else "仅内网")
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            if (network == wifi) localIp.value = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress ?: ""
        }

        override fun onLost(network: Network) {
            flags.remove(network)
            if (wifi == network) {
                wifi = null
                localIp.value = ""
            }
            listener?.onReachability("断开")
        }
    }

    fun start() {
        startedAt = SystemClock.elapsedRealtime()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
    }

    /** Wi-Fi 上本机的 IPv4 地址（门户没告诉我们时用）。 */
    fun wifiIpv4(): String {
        val net = wifi ?: return ""
        return cm.getLinkProperties(net)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address }?.hostAddress ?: ""
    }
}

/** 门户请求绑定到 Wi-Fi 发出；连本机（adb reverse 转发的假门户）时不绑定。 */
class WifiTransport(private val watcher: NetworkWatcher) : UrlTransport() {
    override fun open(url: URL): HttpURLConnection {
        val net = watcher.wifi
        if (net != null && !isLoopback(url.host)) return net.openConnection(url, Proxy.NO_PROXY) as HttpURLConnection
        return super.open(url)
    }

    override fun localIp(host: String): String = watcher.wifiIpv4().ifEmpty { super.localIp(host) }

    private fun isLoopback(host: String) =
        host == "localhost" || runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
}
