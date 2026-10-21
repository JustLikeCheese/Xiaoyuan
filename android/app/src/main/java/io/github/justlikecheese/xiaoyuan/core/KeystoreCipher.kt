package io.github.justlikecheese.xiaoyuan.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android 密钥库里的 AES 密钥加密密码（作用同桌面端的 DPAPI）：密钥不能导出，只有本机的本应用能解开，
 * 卸载重装或换设备后解不开，账号会标成“需重新输入”。格式 “ks:” + Base64(IV + 密文)。
 */
class KeystoreCipher(private val alias: String = "xiaoyuan_accounts") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun protect(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "ks:" + Base64.getEncoder().encodeToString(cipher.iv + body)
    }

    override fun unprotect(cipher: String): String = try {
        when {
            cipher.startsWith("ks:") -> {
                val bytes = Base64.getDecoder().decode(cipher.substring(3))
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
                String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
            }
            else -> PlainCipher.unprotect(cipher)   // 桌面端拷来的 DPAPI 密文解不开，得到空串
        }
    } catch (_: Exception) {
        ""
    }
}
