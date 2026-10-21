package io.github.justlikecheese.xiaoyuan.portal

import java.io.IOException
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URL

/** 一次 GET 的结果：HTTP 状态码、响应头里的 charset（没有为 null）和响应体。 */
class HttpResult(val code: Int, val charset: String?, val body: ByteArray)

/**
 * 门户请求怎么发出去。桌面端直接用系统网络；安卓端要绑定到 Wi-Fi：校园网登录前系统认为 Wi-Fi “无法上网”，
 * 开着移动数据时默认走流量，请求就到不了门户（见 NetworkWatcher）。
 */
interface Transport {
    /** 连不上抛 IOException，超时抛 SocketTimeoutException。 */
    @Throws(IOException::class)
    fun get(url: String, timeoutMs: Int, headers: Map<String, String>): HttpResult

    /** 门户没告诉我们本机 IP 时，自己查一个（连门户用的那个网卡的地址），查不到返回空串。 */
    fun localIp(host: String): String
}

/** 用 HttpURLConnection 发请求，不走系统代理（门户在内网）。 */
open class UrlTransport : Transport {
    protected open fun open(url: URL): HttpURLConnection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection

    override fun get(url: String, timeoutMs: Int, headers: Map<String, String>): HttpResult {
        val conn = open(URL(url))
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            headers.forEach(conn::setRequestProperty)
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            return HttpResult(code, charsetOf(conn.contentType), body)
        } finally {
            conn.disconnect()
        }
    }

    override fun localIp(host: String): String = try {
        DatagramSocket().use { s ->
            s.connect(InetAddress.getByName(host), 80)
            s.localAddress.hostAddress ?: ""
        }
    } catch (_: Exception) {
        ""
    }

    companion object {
        fun charsetOf(contentType: String?): String? =
            contentType?.split(';')?.map { it.trim() }
                ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter('=')?.trim('"', ' ')?.takeIf { it.isNotEmpty() }
    }
}
