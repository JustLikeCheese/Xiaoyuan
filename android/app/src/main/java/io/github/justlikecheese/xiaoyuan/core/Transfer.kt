package io.github.justlikecheese.xiaoyuan.core

import io.github.justlikecheese.xiaoyuan.portal.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 账号迁移：把选中的账号（含密码）加密成一段文字，存成文件或显示成二维码，在另一台设备上用导入码解开。
 * 格式和桌面端 transfer.py 完全一样（两边能互相导入），说明见那边的文件头。
 */
object Transfer {
    const val PREFIX = "XYACC1:"
    const val ITERATIONS = 100_000
    const val MIN_CODE = 4
    private const val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"   // 去掉了容易看错的 0 O 1 I

    class TransferException(message: String) : Exception(message)

    fun randomCode(length: Int = 8): String {
        val r = SecureRandom()
        return String(CharArray(length) { CODE_ALPHABET[r.nextInt(CODE_ALPHABET.length)] })
    }

    private fun keys(code: String, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val spec = PBEKeySpec(code.toCharArray(), salt, ITERATIONS, 64 * 8)
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return k.copyOfRange(0, 32) to k.copyOfRange(32, 64)
    }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach(mac::update)
        return mac.doFinal()
    }

    private fun stream(key: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var counter = 0
        var i = 0
        while (i < data.size) {
            val block = hmac(key, byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte()))
            for (b in block) {
                if (i >= data.size) break
                out[i] = (data[i].toInt() xor b.toInt()).toByte()
                i++
            }
            counter++
        }
        return out
    }

    private fun tag(key: ByteArray, salt: ByteArray, body: ByteArray): ByteArray =
        hmac(key, PREFIX.dropLast(1).toByteArray(), salt, body).copyOf(16)

    fun encode(accounts: List<Account>, code: String, salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }): String {
        if (code.length < MIN_CODE) throw TransferException("导入码至少 $MIN_CODE 位")
        val json = buildJsonObject {
            put("v", 1)
            put("accounts", buildJsonArray {
                for (a in accounts) add(buildJsonObject {
                    put("a", a.account)
                    put("i", a.isp)
                    put("n", a.note)
                    put("p", a.password)
                    put("t", a.terminalType)
                })
            })
        }.toString().toByteArray()
        val plain = Deflater(9).run {
            setInput(json)
            finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (!finished()) out.write(buf, 0, deflate(buf))
            end()
            out.toByteArray()
        }
        val (enc, mac) = keys(code, salt)
        val body = stream(enc, plain)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(salt + body + tag(mac, salt, body))
    }

    fun looksLike(text: String) = PREFIX in text

    /** 解开迁移数据（前后有别的文字、中间有换行也行），返回的账号 lastUsed 为 0。 */
    fun decode(text: String, code: String): List<Account> {
        val i = text.indexOf(PREFIX)
        if (i < 0) throw TransferException("不是校园网登录的账号迁移数据")
        val b64 = Regex("[A-Za-z0-9_-]*").find(text.substring(i + PREFIX.length).filterNot { it.isWhitespace() })!!.value
        val blob = try {
            Base64.getUrlDecoder().decode(b64)
        } catch (_: IllegalArgumentException) {
            throw TransferException("迁移数据不完整")
        }
        if (blob.size < 16 + 16 + 1) throw TransferException("迁移数据不完整")
        val salt = blob.copyOfRange(0, 16)
        val body = blob.copyOfRange(16, blob.size - 16)
        val got = blob.copyOfRange(blob.size - 16, blob.size)
        val (enc, mac) = keys(code, salt)
        if (!MessageDigest.isEqual(tag(mac, salt, body), got)) throw TransferException("导入码不对")
        try {
            val plain = Inflater().run {
                setInput(stream(enc, body))
                val out = ByteArrayOutputStream()
                val buf = ByteArray(1024)
                while (!finished()) {
                    val n = inflate(buf)
                    if (n == 0 && (needsInput() || needsDictionary())) break
                    out.write(buf, 0, n)
                }
                end()
                out.toByteArray()
            }
            val items = (Json.parseToJsonElement(String(plain)) as JsonObject)["accounts"] as JsonArray
            return items.mapNotNull { it as? JsonObject }.filter { it["a"].text().isNotBlank() }.map {
                Account(it["a"].text(), it["i"].text(), it["n"].text(), it["p"].text(),
                    terminalType = it["t"].text().toIntOrNull() ?: -1)
            }
        } catch (e: TransferException) {
            throw e
        } catch (_: DataFormatException) {
            throw TransferException("迁移数据已损坏")
        } catch (_: Exception) {
            throw TransferException("迁移数据已损坏")
        }
    }
}
