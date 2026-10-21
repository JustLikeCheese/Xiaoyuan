package io.github.justlikecheese.xiaoyuan.core

import android.net.TrafficStats
import android.os.Debug
import android.os.SystemClock
import io.github.justlikecheese.xiaoyuan.portal.Transport
import io.github.justlikecheese.xiaoyuan.portal.UrlTransport
import io.github.justlikecheese.xiaoyuan.portal.str
import io.github.justlikecheese.xiaoyuan.portal.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.charset.Charset

/** 本机的流量和内存（首页“流量统计”“内存信息”卡片用）。 */
object DeviceStats {
    /** 开机以来 Wi-Fi 的（下载，上传）字节数：全部网卡减去移动数据。系统不支持时为 0。 */
    fun wifiBytes(): Pair<Long, Long> {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return 0L to 0L
        val mrx = TrafficStats.getMobileRxBytes().coerceAtLeast(0)
        val mtx = TrafficStats.getMobileTxBytes().coerceAtLeast(0)
        return (rx - mrx).coerceAtLeast(0) to (tx - mtx).coerceAtLeast(0)
    }

    /** 这次开机的时间（毫秒），用来判断记下的流量起点是不是重启前的。 */
    fun bootTime(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /** 本应用占用的内存（PSS，字节）。 */
    fun appMemory(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss * 1024L
    }
}

/** 公网 IP 和归属地。place 如“湖南 长沙”（国外会带国家），isp 如“移动”。 */
data class PublicIp(val ip: String, val place: String, val isp: String, val source: String)

/**
 * 查公网 IP 的归属地和运营商：先问 ipip.net（国内地址精确到市、运营商是中文），查不到再问 ip-api.com。
 * 请求会让这两个服务看到本机的公网 IP；只在首页显示时、在线时查，结果缓存，不写进日志。
 */
object IpLookup {
    private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    fun query(transport: Transport): PublicIp {
        val errors = mutableListOf<String>()
        for ((name, fn) in listOf<Pair<String, (Transport) -> PublicIp>>("ipip.net" to ::ipip, "ip-api.com" to ::ipApi)) {
            try {
                return fn(transport)
            } catch (e: Exception) {
                errors += "$name：${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IOException(errors.joinToString("；"))
    }

    /** 先从 Wi-Fi 查（校园网的出口）；Wi-Fi 被别的 VPN 占着发不出去时，改走系统默认网络。 */
    fun queryWithFallback(transport: Transport): PublicIp = try {
        query(transport)
    } catch (e: IOException) {
        if (transport is UrlTransport && transport.javaClass != UrlTransport::class.java) query(UrlTransport()) else throw e
    }

    private fun json(transport: Transport, url: String): JsonObject {
        val r = transport.get(url, 6000, mapOf("User-Agent" to UA, "Accept" to "application/json"))
        if (r.code >= 400) throw IOException("HTTP ${r.code}")
        val text = String(r.body, runCatching { Charset.forName(r.charset ?: "utf-8") }.getOrDefault(Charsets.UTF_8))
        return Json.parseToJsonElement(text) as? JsonObject ?: throw IOException("返回格式不对")
    }

    /** {"ret":"ok","data":{"ip":"…","location":["中国","湖南","长沙","","移动"]}} */
    private fun ipip(transport: Transport): PublicIp {
        val d = json(transport, "https://myip.ipip.net/json")
        val data = d["data"] as? JsonObject
        if (d.str("ret") != "ok" || data == null) throw IOException("返回格式不对")
        val loc = (data["location"] as? JsonArray)?.map { it.text().trim() } ?: emptyList()
        val country = loc.getOrElse(0) { "" }
        val place = listOf(if (country == "中国") "" else country, loc.getOrElse(1) { "" }, loc.getOrElse(2) { "" })
            .filter { it.isNotEmpty() }.distinct().joinToString(" ")
        return PublicIp(data.str("ip"), place, loc.getOrElse(4) { "" }, "ipip.net")
    }

    /** 免费版只有 http；lang=zh-CN 时地名是中文，运营商是英文。 */
    private fun ipApi(transport: Transport): PublicIp {
        val d = json(transport, "http://ip-api.com/json/?lang=zh-CN&fields=status,message,country,regionName,city,isp,query")
        if (d.str("status") != "success") throw IOException(d.str("message").ifEmpty { "查询失败" })
        val country = d.str("country")
        val place = listOf(if (country == "中国") "" else country, d.str("regionName"), d.str("city"))
            .filter { it.isNotEmpty() }.distinct().joinToString(" ")
        return PublicIp(d.str("query"), place, d.str("isp"), "ip-api.com")
    }
}
