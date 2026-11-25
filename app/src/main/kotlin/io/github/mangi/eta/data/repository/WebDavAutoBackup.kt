package io.github.mangi.eta.data.repository

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.WebDavSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * WebDAV 自动备份的闹钟调度，与 MediaIndexAutoUpdate 同一套路：
 * setInexactRepeating（RTC_WAKEUP，首跳 = now + interval），非精确闹钟无需精确闹钟权限。
 *
 * AlarmManager 的周期闹钟在重启后丢失，因此启动时与开机广播都要重排。
 * 关闭自动备份就 cancel 既有闹钟，避免残留。
 */
internal object WebDavAutoBackup {
    const val ACTION_AUTO = "io.github.mangi.eta.action.WEBDAV_AUTO_BACKUP"
    private const val REQUEST_CODE = 0x7EBA01

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 按当前设置重排闹钟；未开启或未配置则取消。 */
    fun schedule(context: Context, settings: WebDavSettings) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(app)
        if (!settings.autoBackupEnabled || !settings.configured) {
            alarmManager.cancel(pending)
            return
        }
        val intervalMs = settings.intervalMillis
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + intervalMs,
            intervalMs,
            pending,
        )
    }

    /** 冷启动兜底：读设置后重排，并在「已过周期」时补跑一次。 */
    fun onAppLaunch(context: Context) {
        scope.launch {
            runCatching {
                val settings = SettingsDataStore.webDavSettings()
                schedule(context, settings)
                if (settings.autoBackupEnabled && settings.configured) {
                    val due = settings.lastBackupAtMillis == 0L ||
                        System.currentTimeMillis() - settings.lastBackupAtMillis >= settings.intervalMillis
                    if (due) WebDavBackupManager.runAuto(context)
                }
            }.onFailure { AndroidAgentLogger.warn("WebDAV auto backup launch failed: ${'$'}{it.safeLogType()}") }
        }
    }

    /** 闹钟到点：跑一轮。结果由 runAuto 自己写回设置。 */
    fun runAuto(context: Context) {
        scope.launch {
            runCatching { WebDavBackupManager.runAuto(context) }
                .onFailure { AndroidAgentLogger.warn("WebDAV auto backup run failed: ${'$'}{it.safeLogType()}") }
        }
    }

    /** 保存设置后调用：立即触发一次（用户要求「立即」），随后按周期重排。 */
    fun onSettingsSaved(context: Context, settings: WebDavSettings) {
        schedule(context, settings)
        if (settings.autoBackupEnabled && settings.configured) runAuto(context)
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WebDavAutoBackupReceiver::class.java).setAction(ACTION_AUTO)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * 自动备份的广播入口（manifest 注册，exported=false）：
 * - 闹钟到点 -> 跑一轮备份
 * - BOOT_COMPLETED -> 重排周期闹钟（AlarmManager 闹钟重启后丢失）
 */
internal class WebDavAutoBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> WebDavAutoBackup.onAppLaunch(context)
            WebDavAutoBackup.ACTION_AUTO -> WebDavAutoBackup.runAuto(context)
        }
    }
}
