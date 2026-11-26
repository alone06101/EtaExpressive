package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.WebDavSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把本地备份接到 WebDAV 上。
 *
 * 备份包的生成与校验仍然只有 EtaBackupRepository 一个出口，这里只负责
 * 「临时文件 + 上传」与「下载 + 交回 import」两段搬运，因此云端恢复与本地文件恢复
 * 走同一套 schema 校验与安全闸，不会有「从云端恢复能绕过检查」的旁路。
 *
 * 临时文件一律放 cacheDir 并在 finally 删除：备份包可能几十兆。
 */
internal object WebDavBackupManager {
    /** 远端文件名前缀，用于来源识别与列表过滤。 */
    const val FILE_PREFIX = "etabackup-"

    /** 防抖窗口：距上次不足周期的 90% 就跳过，避免闹钟漂移导致重复上传。 */
    private const val DEBOUNCE_RATIO = 0.9

    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** 生成备份并上传，返回远端文件名。 */
    suspend fun upload(
        context: Context,
        settings: WebDavSettings,
        options: EtaBackupExportOptions = settings.exportOptions(),
    ): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val password = WebDavSecretCipher.decrypt(settings.passwordEncrypted)
            ?: throw WebDavException("保存的密码无法解密，请重新填写")
        val client = clientFor(settings, password)
        val directory = settings.remoteDirectory.trim().trim('/')
        val fileName = FILE_PREFIX + stamp.format(Date()) + ".zip"
        val temp = File(app.cacheDir, "webdav-upload.zip")
        try {
            temp.outputStream().use { out -> EtaBackupRepository.export(app, out, options) }
            if (directory.isNotEmpty()) client.ensureDirectory(directory)
            client.upload(directory, fileName, temp)
            fileName
        } finally {
            runCatching { temp.delete() }
        }
    }

    /** 云端备份列表；未配置或缺密码返回空表，界面按「云端没有备份」呈现。 */
    suspend fun list(context: Context, settings: WebDavSettings): List<WebDavBackupEntry> =
        withContext(Dispatchers.IO) {
            if (!settings.configured) return@withContext emptyList()
            val password = WebDavSecretCipher.decrypt(settings.passwordEncrypted)
                ?: return@withContext emptyList()
            clientFor(settings, password).list(settings.remoteDirectory.trim().trim('/'))
        }

    /**
     * 从云端恢复。
     *
     * 下载到临时文件后交给 EtaBackupRepository.import：它校验 format 必须是
     * eta-backup、schema 在支持区间内、以及归档条目与体积限制。非本应用生成的包
     * 在第一步就被拒绝，不会进入写库流程。
     */
    suspend fun restore(
        context: Context,
        settings: WebDavSettings,
        fileName: String,
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val password = WebDavSecretCipher.decrypt(settings.passwordEncrypted)
            ?: throw WebDavException("保存的密码无法解密，请重新填写")
        require(fileName.endsWith(".zip", ignoreCase = true)) { "只能恢复 .zip 备份包" }
        val client = clientFor(settings, password)
        val directory = settings.remoteDirectory.trim().trim('/')
        val temp = File(app.cacheDir, "webdav-restore.zip")
        try {
            client.download(directory, fileName, temp)
            temp.inputStream().use { input -> EtaBackupRepository.import(app, input) }
        } finally {
            runCatching { temp.delete() }
        }
    }

    /** 测连通：校验地址、解密密码、PROPFIND 目标目录。 */
    suspend fun test(context: Context, settings: WebDavSettings): Unit = withContext(Dispatchers.IO) {
        val password = WebDavSecretCipher.decrypt(settings.passwordEncrypted)
            ?: throw WebDavException("保存的密码无法解密，请重新填写")
        clientFor(settings, password).probe(settings.remoteDirectory.trim().trim('/'))
    }

    /**
     * 自动备份入口，由闹钟触发。
     *
     * 守卫顺序：未开启 > 未配置 > 防抖窗口内 > 无网络，任一命中就跳过，
     * 原因写进 lastBackupError 供界面展示。不做「仅充电时」限制：备份是用户显式
     * 开启的周期任务，静默跳过会让「每天备份」变成不可预期的随机行为。
     */
    suspend fun runAuto(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val settings = SettingsDataStore.webDavSettings()
        if (!settings.autoBackupEnabled) return@withContext Result.failure(WebDavException("未开启自动备份"))
        if (!settings.configured) return@withContext Result.failure(WebDavException("WebDAV 未配置"))
        val now = System.currentTimeMillis()
        val elapsed = now - settings.lastBackupAtMillis
        if (settings.lastBackupAtMillis > 0 && elapsed < (settings.intervalMillis * DEBOUNCE_RATIO).toLong()) {
            return@withContext Result.success("skip")
        }
        if (!networkAvailable(app)) return@withContext Result.failure(WebDavException("当前无网络"))
        runCatching { upload(app, settings) }
            .onSuccess {
                SettingsDataStore.setWebDavSettings(settings.copy(lastBackupAtMillis = now, lastBackupError = ""))
            }
            .onFailure { error ->
                AndroidAgentLogger.warn("WebDAV auto backup failed: ${'$'}{error.safeLogType()}")
                SettingsDataStore.setWebDavSettings(
                    settings.copy(lastBackupAtMillis = now, lastBackupError = error.message ?: "自动备份失败"),
                )
            }
    }

    /**
     * 手动备份：成功后顺延自动备份的防抖窗口。
     *
     * [options] 由调用方传入页面上的勾选；不传则用设置里记住的那一套。
     */
    suspend fun uploadManually(
        context: Context,
        settings: WebDavSettings,
        options: EtaBackupExportOptions = settings.exportOptions(),
    ): String {
        val fileName = upload(context, settings, options)
        SettingsDataStore.setWebDavSettings(
            settings.copy(lastBackupAtMillis = System.currentTimeMillis(), lastBackupError = ""),
        )
        return fileName
    }

    private fun clientFor(settings: WebDavSettings, password: String): WebDavClient {
        val client = WebDavClient(settings.serverUrl, settings.username, password)
        if (!client.validBase()) throw WebDavException("服务器地址必须是 http 或 https 链接")
        return client
    }

    private fun networkAvailable(context: Context): Boolean {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/**
 * 把界面上的勾选固化进设置，供本次与后续自动备份复用。
 *
 * 云端若固定用默认选项，同一份数据会比本地导出小一大截（Linux 环境、模型、
 * 浏览器设置都不进包），用户看到的就是「两边大小明显不一样」。
 */
internal fun WebDavSettings.withExportOptions(options: EtaBackupExportOptions): WebDavSettings = copy(
    includeAppData = options.includeAppData,
    includeLinuxEnvironment = options.includeLinuxEnvironment,
    includeEmbeddings = options.includeEmbeddings,
    includeModels = options.includeModels,
    includeBrowserSettings = options.includeBrowserSettings,
    // 密钥只存在于应用数据里，没勾应用数据就必然不带。
    includeApiKeys = options.includeApiKeys && options.includeAppData,
)

/** 还原成导出选项；手动与自动备份都走这一个出口。 */
internal fun WebDavSettings.exportOptions(): EtaBackupExportOptions = EtaBackupExportOptions(
    includeAppData = includeAppData,
    includeApiKeys = includeApiKeys && includeAppData,
    includeBrowserSettings = includeBrowserSettings,
    includeLinuxEnvironment = includeLinuxEnvironment,
    includeEmbeddings = includeEmbeddings,
    includeModels = includeModels,
)
