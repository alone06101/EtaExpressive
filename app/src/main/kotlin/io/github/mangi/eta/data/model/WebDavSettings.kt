package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * WebDAV 云端备份配置。
 *
 * passwordEncrypted 存的是 WebDavSecretCipher 产出的密文（前缀 enc1.），从不落明文；
 * 解密失败按「未配置」处理，不阻断其它功能。配置本身只是目标地址与账号，不含备份内容。
 */
@Serializable
data class WebDavSettings(
    val serverUrl: String = "",
    /** 备份包在 WebDAV 上的目录，相对服务器根；空串表示服务器根。 */
    val remoteDirectory: String = DEFAULT_DIRECTORY,
    val username: String = "",
    val passwordEncrypted: String = "",
    val autoBackupEnabled: Boolean = false,
    /** 自动备份周期：INTERVAL_DAILY 或 INTERVAL_WEEKLY。 */
    val autoBackupInterval: String = INTERVAL_DAILY,
    /** 上次备份开始时间（毫秒），用于防抖与界面展示。 */
    val lastBackupAtMillis: Long = 0L,
    /** 上次备份结果：成功为空串，失败为简短原因，仅用于界面提示。 */
    val lastBackupError: String = "",
) {
    val configured: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && passwordEncrypted.isNotBlank()

    val intervalMillis: Long
        get() = when (autoBackupInterval) {
            INTERVAL_WEEKLY -> WEEK_MILLIS
            else -> DAY_MILLIS
        }

    companion object {
        const val DEFAULT_DIRECTORY = "EtaBackup"
        const val INTERVAL_DAILY = "daily"
        const val INTERVAL_WEEKLY = "weekly"
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        private const val WEEK_MILLIS = 7L * DAY_MILLIS
    }
}
