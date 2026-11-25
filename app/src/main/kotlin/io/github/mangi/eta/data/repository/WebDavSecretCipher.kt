package io.github.mangi.eta.data.repository

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.mangi.eta.core.AndroidAgentLogger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * WebDAV 密码的硬件加密存储。
 *
 * 与 ProviderSecretCipher 同一套做法：AndroidKeyStore 里的 AES-GCM 密钥，密钥不出
 * TEE/StrongBox，密文前缀 enc1. 便于识别。AAD 绑定 webdav.password，避免密文被挪作他用。
 *
 * 刻意不复用 Provider 那边的 key alias：任何一侧将来的密钥策略变化（例如加生物识别）
 * 都不该牵连另一侧。解密失败返回 null，调用方按「未配置」处理。
 */
internal object WebDavSecretCipher {
    private const val PREFIX = "enc1."
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "eta_webdav_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val AAD = "webdav.password"

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    /** 加密。空串返回空串；密钥不可用时返回 null，由调用方决定是否拒绝保存。 */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        }.getOrElse {
            AndroidAgentLogger.warn("WebDAV password encryption unavailable: ${it.javaClass.simpleName}")
            null
        }
    }

    /** 解密。无前缀视为历史明文，原样返回；解密失败返回 null。 */
    fun decrypt(value: String): String? {
        if (value.isEmpty()) return ""
        if (!isEncrypted(value)) return value
        return runCatching {
            val payload = Base64.decode(value.substring(PREFIX.length), Base64.NO_WRAP)
            require(payload.size > GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_BYTES),
            )
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            cipher.doFinal(payload, GCM_IV_BYTES, payload.size - GCM_IV_BYTES).toString(Charsets.UTF_8)
        }.getOrElse {
            AndroidAgentLogger.warn("WebDAV password decryption failed: ${it.javaClass.simpleName}")
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }
}
