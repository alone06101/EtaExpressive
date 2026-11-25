package io.github.mangi.eta.ui.screens.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.WebDavSettings
import io.github.mangi.eta.data.repository.WebDavAutoBackup
import io.github.mangi.eta.data.repository.WebDavBackupEntry
import io.github.mangi.eta.data.repository.WebDavBackupManager
import io.github.mangi.eta.data.repository.WebDavSecretCipher
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WebDAV 云端备份面板。
 *
 * 密码永远以密文落盘，界面只显示「已保存」；用户可点眼睛图标临时切明文视图核对输入，
 * 但切换不写回明文。网络操作都在 IO 线程，完成时刷新设置快照——自动与手动备份共享
 * 同一个 lastBackupAtMillis，防抖窗口才不会各算各的。
 */
@Composable
internal fun WebDavBackupSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(WebDavSettings()) }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf(WebDavSettings.DEFAULT_DIRECTORY) }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var remoteEntries by remember { mutableStateOf<List<WebDavBackupEntry>>(emptyList()) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { SettingsDataStore.webDavSettings() }
        settings = loaded
        serverUrl = loaded.serverUrl
        username = loaded.username
        directory = loaded.remoteDirectory
    }

    // 组装待保存设置；密码留空则沿用已有密文。
    fun draft(): WebDavSettings {
        val encrypted = if (passwordInput.isEmpty()) {
            settings.passwordEncrypted
        } else {
            WebDavSecretCipher.encrypt(passwordInput) ?: settings.passwordEncrypted
        }
        return settings.copy(
            serverUrl = serverUrl.trim(),
            username = username.trim(),
            remoteDirectory = directory.trim().ifBlank { WebDavSettings.DEFAULT_DIRECTORY },
            passwordEncrypted = encrypted,
        )
    }

    fun persist(next: WebDavSettings, restartAlarm: Boolean = true) {
        scope.launch {
            withContext(Dispatchers.IO) { SettingsDataStore.setWebDavSettings(next) }
            settings = next
            passwordInput = ""
            if (restartAlarm) WebDavAutoBackup.onSettingsSaved(context, next)
        }
    }

    fun run(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = context.getString(R.string.webdav_working)
        scope.launch {
            val result = runCatching { block() }
            status = result.exceptionOrNull()?.message?.let { "$label: $it" }
                ?: "$label: ${context.getString(R.string.webdav_done)}"
            busy = false
        }
    }

    EtaSegmentedColumn(
        modifier = modifier
            .padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
        title = stringResource(R.string.webdav_section_title),
    ) {
        item(surface = false) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                WebDavField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = stringResource(R.string.webdav_server_url),
                    placeholder = "https://example.com/dav",
                )
                WebDavField(
                    value = username,
                    onValueChange = { username = it },
                    label = stringResource(R.string.webdav_username),
                )
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { M3Text(stringResource(R.string.webdav_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = stringResource(R.string.webdav_toggle_password),
                            )
                        }
                    },
                )
                M3Text(
                    text = if (settings.passwordEncrypted.isNotEmpty()) {
                        stringResource(R.string.webdav_password_saved)
                    } else {
                        stringResource(R.string.webdav_password_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                )
                WebDavField(
                    value = directory,
                    onValueChange = { directory = it },
                    label = stringResource(R.string.webdav_directory),
                    placeholder = WebDavSettings.DEFAULT_DIRECTORY,
                )
            }
        }
        item {
            EtaSwitchRow(
                title = stringResource(R.string.webdav_auto_title),
                summary = stringResource(R.string.webdav_auto_summary),
                checked = settings.autoBackupEnabled,
                enabled = !busy,
                onCheckedChange = { enabled ->
                    val next = draft().copy(autoBackupEnabled = enabled)
                    persist(next)
                    // 开启时立即跑一次（用户明确要求），关掉则只取消闹钟。
                    if (enabled) run(context.getString(R.string.webdav_upload_label)) {
                        WebDavBackupManager.uploadManually(context, next)
                    }
                },
            )
        }
        item {
            EtaSwitchRow(
                title = stringResource(R.string.webdav_weekly_title),
                summary = stringResource(R.string.webdav_weekly_summary),
                checked = settings.autoBackupInterval == WebDavSettings.INTERVAL_WEEKLY,
                enabled = !busy && settings.autoBackupEnabled,
                onCheckedChange = { weekly ->
                    val next = draft().copy(
                        autoBackupInterval = if (weekly) WebDavSettings.INTERVAL_WEEKLY else WebDavSettings.INTERVAL_DAILY,
                    )
                    persist(next)
                },
            )
        }
        item {
            BackupActionRow(
                title = stringResource(R.string.webdav_test),
                summary = stringResource(R.string.webdav_test_summary),
                icon = Icons.Rounded.CloudUpload,
                loading = busy,
                enabled = !busy,
                onClick = {
                    val next = draft()
                    persist(next, restartAlarm = false)
                    run(context.getString(R.string.webdav_test)) { WebDavBackupManager.test(context, next) }
                },
            )
        }
        item {
            BackupActionRow(
                title = stringResource(R.string.webdav_upload_label),
                summary = stringResource(R.string.webdav_upload_summary),
                icon = Icons.Rounded.CloudUpload,
                loading = busy,
                enabled = !busy,
                onClick = {
                    val next = draft()
                    persist(next, restartAlarm = false)
                    run(context.getString(R.string.webdav_upload_label)) {
                        WebDavBackupManager.uploadManually(context, next)
                        val refreshed = SettingsDataStore.webDavSettings()
                        withContext(Dispatchers.Main) { settings = refreshed }
                    }
                },
            )
        }
        item {
            BackupActionRow(
                title = stringResource(R.string.webdav_list),
                summary = if (remoteEntries.isEmpty()) {
                    stringResource(R.string.webdav_list_summary)
                } else {
                    stringResource(R.string.webdav_list_count, remoteEntries.size)
                },
                icon = Icons.Rounded.CloudDownload,
                loading = busy,
                enabled = !busy,
                onClick = {
                    val next = draft()
                    persist(next, restartAlarm = false)
                    run(context.getString(R.string.webdav_list)) {
                        val entries = WebDavBackupManager.list(context, next)
                        withContext(Dispatchers.Main) { remoteEntries = entries }
                    }
                },
            )
        }
        // 远端列表：恢复是破坏性操作，逐条列出、点谁恢复谁，不做「一键恢复最新」。
        remoteEntries.take(5).forEach { entry ->
            item(key = "remote_" + entry.fileName) {
                BackupActionRow(
                    title = entry.fileName,
                    summary = entry.lastModified.ifBlank { stringResource(R.string.webdav_restore_summary) },
                    icon = Icons.Rounded.CloudDownload,
                    loading = busy,
                    enabled = !busy,
                    onClick = {
                        val next = draft()
                        run(context.getString(R.string.webdav_restore_label)) {
                            WebDavBackupManager.restore(context, next, entry.fileName)
                        }
                    },
                )
            }
        }
        status?.let { message ->
            item(key = "webdav_status") {
                M3Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun WebDavField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { M3Text(label) },
        placeholder = placeholder?.let { hint -> { M3Text(hint) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
