package io.github.justlikecheese.xiaoyuan.core

import io.github.justlikecheese.xiaoyuan.portal.DEFAULT_HOST
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/** settings.json，分组结构和桌面端一样（Login / Portal / Appearance / Status / Reconnect）。 */
class Settings(private val file: File) {
    private val data: MutableMap<String, MutableMap<String, JsonElement>> = load()

    private fun load(): MutableMap<String, MutableMap<String, JsonElement>> = try {
        (Json.parseToJsonElement(file.readText()) as JsonObject).mapValuesTo(LinkedHashMap()) { (_, v) ->
            ((v as? JsonObject) ?: JsonObject(emptyMap())).toMutableMap()
        }
    } catch (_: Exception) {
        LinkedHashMap()
    }

    private fun raw(group: String, name: String): JsonPrimitive? = data[group]?.get(name) as? JsonPrimitive

    // 类型和默认值不符时用默认值，同桌面端 Settings.get
    fun bool(group: String, name: String): Boolean = raw(group, name)?.takeIf { !it.isString }?.booleanOrNull
        ?: DEFAULTS.getValue(group to name) as Boolean

    fun int(group: String, name: String): Int = raw(group, name)?.takeIf { !it.isString }?.intOrNull
        ?: DEFAULTS.getValue(group to name) as Int

    fun string(group: String, name: String): String = raw(group, name)?.takeIf { it.isString }?.content
        ?: DEFAULTS.getValue(group to name) as String

    @Synchronized
    fun set(group: String, name: String, value: Any) {
        data.getOrPut(group) { LinkedHashMap() }[name] = when (value) {
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(PRETTY.encodeToString(JsonElement.serializer(), JsonObject(data.mapValues { JsonObject(it.value) })))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "    " }

        val DEFAULTS: Map<Pair<String, String>, Any> = mapOf(
            ("Login" to "AutoLogin") to false,
            ("Login" to "CloseAfterAutoLogin") to false,
            ("Login" to "FormExpanded") to true,      // 首页“账号登录”卡片是否展开
            ("Portal" to "Host") to "http://$DEFAULT_HOST",
            ("Portal" to "TerminalType") to 2,        // 登录时告诉门户的终端类型；手机上默认“手机”（桌面端默认电脑）
            ("Appearance" to "Theme") to "Auto",      // Light / Dark / Auto
            ("Appearance" to "Color") to "dynamic",   // 主题颜色：dynamic 跟随壁纸取色，或 #RRGGBB
            ("Status" to "AutoRefresh") to false,
            ("Reconnect" to "Enabled") to false,
            ("Reconnect" to "Interval") to 60,        // 定时检测间隔（秒）；网络变化时会立即检测，不靠这个
            ("Reconnect" to "Account") to "",         // 空串表示最近使用的账号
            ("Reconnect" to "Paused") to false,       // 手动注销或连续失败后暂停，再次手动登录或点“恢复”解除
            ("Reconnect" to "Since") to "",          // 开启自动重连的时间（毫秒）
            ("Reconnect" to "Failures") to 0,         // 连续失败次数，到 3 次暂停
            ("Reconnect" to "NotBefore") to "",       // 失败后的冷却截止时间（毫秒时间戳）
            ("Guard" to "Notify") to false,           // 守护通知：通知栏显示保活守护的状态，可以检测、停止
            ("Guard" to "Mode") to "service",         // 保活方式：service 前台服务 / root / adb（守护程序）
            ("Session" to "Account") to "",           // 本次登录的账号（门户返回的 uid，小写）
            ("Session" to "Since") to "",             // 本次登录开始的时间（毫秒），首页按钮上显示已连接多久
            ("Session" to "Rx0") to "",               // 本次登录开始时 Wi-Fi 的下载、上传计数和开机时间，流量统计从这里算
            ("Session" to "Tx0") to "",
            ("Session" to "Boot") to "",
            ("Notice" to "Dismissed") to "",          // 选了“不再显示”的那条公告的摘要
            ("Notice" to "SnoozeDay") to "",          // 选了“今天不再显示”的日期
        )
    }
}
