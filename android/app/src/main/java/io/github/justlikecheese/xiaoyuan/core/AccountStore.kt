package io.github.justlikecheese.xiaoyuan.core

import io.github.justlikecheese.xiaoyuan.portal.carrierName
import io.github.justlikecheese.xiaoyuan.portal.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

class Account(
    var account: String,
    var isp: String = "",           // 运营商后缀，如 @telecom
    var note: String = "",
    var password: String = "",      // 仅在内存中是明文
    var lastUsed: Double = 0.0,     // 秒（同桌面端 time.time()）
    var terminalType: Int = -1,     // 登录时的终端类型（TERMINAL_TYPES 的值）；-1 表示默认，跟随全局设置
    var passwordLost: Boolean = false,   // 保存过密码但解不开
) {
    val full: String get() = account + isp
    val carrier: String get() = carrierName(isp)
}

/** 把密码加密成文字保存；解不开（换了设备、数据损坏）时 unprotect 返回空串。 */
interface SecretCipher {
    fun protect(plain: String): String
    fun unprotect(cipher: String): String
}

/**
 * 账号保存，对应桌面端 store.py。数据放在应用私有目录的 accounts.json，格式和桌面端一样；
 * 密码用 Android 密钥库加密（KeystoreCipher），只有本机的本应用能解开。
 */
class AccountStore(val file: File, private val cipher: SecretCipher) {
    var accounts: MutableList<Account> = mutableListOf()
        private set

    init {
        load()
    }

    fun load() {
        accounts = mutableListOf()
        val items = try {
            (Json.parseToJsonElement(file.readText()) as JsonObject)["accounts"] as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return
        for (item in items) {
            if (item !is JsonObject || item["account"].text().isEmpty()) continue
            val stored = item["password"].text()
            val password = if (stored.isEmpty()) "" else cipher.unprotect(stored)
            accounts += Account(
                account = item["account"].text(),
                isp = item["isp"].text(),
                note = item["note"].text(),
                password = password,
                lastUsed = item["last_used"].text().toDoubleOrNull() ?: 0.0,
                terminalType = item["terminal_type"].text().toIntOrNull() ?: -1,
                passwordLost = stored.isNotEmpty() && password.isEmpty(),
            )
        }
    }

    fun save() {
        val json = buildJsonObject {
            put("accounts", buildJsonArray {
                for (a in accounts) add(buildJsonObject {
                    put("account", a.account)
                    put("isp", a.isp)
                    put("note", a.note)
                    put("password", if (a.password.isEmpty()) "" else cipher.protect(a.password))
                    put("last_used", a.lastUsed)
                    put("terminal_type", a.terminalType)
                })
            })
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(PRETTY.encodeToString(JsonElement.serializer(), json))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun get(account: String): Account? = accounts.firstOrNull { it.account == account }

    /** 按账号新增或覆盖；touch 为 true 时记为最近使用。 */
    fun upsert(acc: Account, touch: Boolean = false): Account {
        val existing = get(acc.account)
        val target = if (existing != null) {
            existing.isp = acc.isp
            existing.note = acc.note
            existing.password = acc.password
            existing.terminalType = acc.terminalType
            existing.passwordLost = false
            existing
        } else {
            accounts += acc
            acc
        }
        if (touch) target.lastUsed = System.currentTimeMillis() / 1000.0
        save()
        return target
    }

    /** 编辑时账号可能被改掉，先删旧的再写入。 */
    fun rename(oldAccount: String, acc: Account): Account {
        val old = get(oldAccount)
        if (old != null && oldAccount != acc.account) {
            acc.lastUsed = maxOf(acc.lastUsed, old.lastUsed)
            accounts.remove(old)
        }
        return upsert(acc)
    }

    fun remove(account: String) {
        accounts = accounts.filter { it.account != account }.toMutableList()
        save()
    }

    fun last(): Account? = accounts.filter { it.lastUsed > 0 }.maxByOrNull { it.lastUsed } ?: accounts.firstOrNull()

    companion object {
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }
    }
}

/** 不加密，只做 Base64（JVM 单元测试用；格式同桌面端非 Windows 时的 “b64:” 前缀）。 */
object PlainCipher : SecretCipher {
    override fun protect(plain: String) = "b64:" + java.util.Base64.getEncoder().encodeToString(plain.toByteArray())

    override fun unprotect(cipher: String): String = try {
        if (cipher.startsWith("b64:")) String(java.util.Base64.getDecoder().decode(cipher.substring(4))) else ""
    } catch (_: IllegalArgumentException) {
        ""
    }
}
