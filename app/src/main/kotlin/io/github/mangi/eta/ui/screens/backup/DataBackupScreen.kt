package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import android.text.format.Formatter
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.EtaBackupArchiveKind
import io.github.mangi.eta.data.repository.EtaBackupExportOptions
import io.github.mangi.eta.data.repository.EtaBackupRepository
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.data.repository.EtaModelBackupSummary
import io.github.mangi.eta.agent.tool.ToolRecipePaths
import io.github.mangi.eta.ui.components.WorkingDialog
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaLoadingSlot
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedCheckboxRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.components.PreferenceIcon
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text as M3Text

/**
 * 数据备份页：上半部分是「要备份哪些内容」的类别勾选，下半部分只有一对导出 / 导入。
 *
 * 类别之间是并列关系，导出时按勾选结果打包；导入端自己认归档类型
 * （数据备份 / 纯模型备份），所以不需要再给模型单独配一对按钮。
 */
@Composable
internal fun DataBackupScreen(
    context: Context,
    onBack: () -> Unit,
    onExport: suspend (OutputStream, EtaBackupExportOptions) -> EtaBackupSummary,
    onImport: suspend (InputStream) -> EtaBackupSummary,
    onImportModels: suspend (InputStream) -> EtaModelBackupSummary,
    onImportAnalysis: suspend (String) -> String,
    onApplyImportedBinding: suspend (String, Boolean) -> Boolean = { _, _ -> false },
    /** 待用户选择绑定的导入会话 id；由宿主状态提供，避免重整时丢失。 */
    pendingImportedBindingId: String? = null,
    onDismissImportedBinding: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(false) }
    var importingAnalysis by remember { mutableStateOf(false) }
    val busy = exporting || importing || detecting || importingAnalysis
    // 类别勾选：应用数据默认带上，Linux 环境 / 模型这类大件默认不带。
    var includeAppData by remember { mutableStateOf(true) }
    var includeLinuxEnvironment by remember { mutableStateOf(false) }
    var includeEmbeddings by remember { mutableStateOf(true) }
    var includeModels by remember { mutableStateOf(false) }
    // 浏览器设置（UA / 视口 / 空闲超时）：可选迁移项，默认不带。
    var includeBrowserSettings by remember { mutableStateOf(false) }
    // API Key 明文：默认不带。归档不加密，包一离开设备就等于交出全部厂商的账单权限。
    var includeApiKeys by remember { mutableStateOf(false) }
    var linuxBytes by remember { mutableStateOf<Long?>(null) }
    var mediaBytes by remember { mutableStateOf<Long?>(null) }
    var modelSummary by remember { mutableStateOf(EtaModelBackupSummary.EMPTY) }
    var recipeCount by remember { mutableStateOf(0) }
    var recipeBytes by remember { mutableStateOf(0L) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    LaunchedEffect(Unit) {
        linuxBytes = withContext(Dispatchers.IO) {
            EtaBackupRepository.linuxEnvironmentBytes(context)
        }
        mediaBytes = withContext(Dispatchers.IO) {
            EtaBackupRepository.mediaIndexEstimateBytes(context)
        }
        modelSummary = EtaBackupRepository.downloadedModels(context)
        // 配方随应用数据一起进备份包，这里只报数量与体积，让用户知道备份里有什么。
        withContext(Dispatchers.IO) {
            recipeCount = ToolRecipePaths.count(context)
            recipeBytes = ToolRecipePaths.bytes(context)
        }
    }
    val linuxSizeLabel = linuxBytes?.takeIf { it > 0L }?.let { Formatter.formatShortFileSize(context, it) }
    val mediaSizeLabel = mediaBytes?.takeIf { it > 0L }?.let { Formatter.formatShortFileSize(context, it) }
    // 一个都不勾时没法导出：直接把按钮按死，别让人拿到空包。
    val hasSelection = includeAppData || includeLinuxEnvironment || includeEmbeddings ||
        includeModels || includeBrowserSettings

    fun showFailure(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        Toast.makeText(
            context,
            throwable.message ?: context.getString(R.string.data_backup_failed),
            Toast.LENGTH_LONG,
        ).show()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            exporting = true
            try {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(context.getString(R.string.data_backup_file_open_failed))
                val withData = includeAppData
                val summary = output.use {
                    onExport(
                        it,
                        EtaBackupExportOptions(
                            includeAppData = withData,
                            // 密钥只存在于应用数据里，没勾应用数据就必然不带。
                            includeApiKeys = includeApiKeys && withData,
                            includeBrowserSettings = includeBrowserSettings,
                            includeLinuxEnvironment = includeLinuxEnvironment,
                            includeEmbeddings = includeEmbeddings,
                            includeModels = includeModels,
                        ),
                    )
                }
                val base = if (withData) {
                    context.getString(
                        R.string.data_backup_exported,
                        summary.conversationCount,
                        summary.providerCount,
                        summary.assistantCount,
                    )
                } else {
                    context.getString(R.string.data_backup_exported_selection)
                }
                val message = backupMessage(
                    context,
                    base,
                    R.string.data_backup_exported_models,
                    summary.downloadedModelCount,
                )
                // 明确说一句包里没有密钥，免得换机后才发现要重填。
                val withoutKeys = withData && !includeApiKeys
                Toast.makeText(
                    context,
                    if (withoutKeys) {
                        "$message\n" + context.getString(R.string.data_backup_export_without_keys)
                    } else {
                        message
                    },
                    if (withoutKeys) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
            } catch (throwable: Throwable) {
                showFailure(throwable)
            } finally {
                exporting = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            detecting = true
            try {
                val kind = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error(context.getString(R.string.data_backup_file_open_failed))
                    input.use { EtaBackupRepository.archiveKind(it) }
                }
                if (kind == EtaBackupArchiveKind.UNKNOWN) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.data_backup_unknown_archive),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    pendingImport = PendingImport(uri, kind)
                }
            } catch (throwable: Throwable) {
                showFailure(throwable)
            } finally {
                detecting = false
            }
        }
    }

    val analysisImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importingAnalysis = true
            try {
                val raw = withContext(Dispatchers.IO) {
                    // 先看大小再读：readBytes() 之后才知道体积的话，内存已经爆了。
                    val declared = context.contentResolver
                        .query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                    require(declared == null || declared <= MAX_ANALYSIS_FILE_BYTES) {
                        context.getString(R.string.conversation_analysis_file_too_large)
                    }
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error(context.getString(R.string.data_backup_file_open_failed))
                    input.use { stream ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val read = stream.read(chunk)
                            if (read <= 0) break
                            buffer.write(chunk, 0, read)
                            require(buffer.size() <= MAX_ANALYSIS_FILE_BYTES) {
                                context.getString(R.string.conversation_analysis_file_too_large)
                            }
                        }
                        // 直接按字节解码：toByteArray() 会先复制整份再转 String，
                        // 大文件时这一份拷贝正好是压垮内存的那一份。
                        @Suppress("DEPRECATION")
                        buffer.toString(Charsets.UTF_8.name())
                    }
                }
                val importedId = onImportAnalysis(raw)
                Toast.makeText(
                    context,
                    context.getString(R.string.conversation_analysis_imported),
                    Toast.LENGTH_SHORT,
                ).show()
                // 待决状态由 AgentAppState 发布（pendingImportedBindingId），
                // 这里只负责触发导入；弹窗在页面底部按宿主状态渲染。
                importedId
            } catch (throwable: Throwable) {
                showFailure(throwable)
            } finally {
                importingAnalysis = false
            }
        }
    }

    EtaScaffoldPage(
        title = stringResource(R.string.data_backup_title),
        onBack = onBack,
    ) {
        item(key = "warning") {
            // 纯描述性文字不套分段卡片：直接排在页面背景上，
            // 容器底色只留给下方可交互的分组，视觉层级更清楚。
            BackupNoticeRow(
                modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
                title = stringResource(R.string.data_backup_warning_title),
                summary = stringResource(R.string.data_backup_warning_summary),
            )
        }
        item(key = "categories") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
                title = stringResource(R.string.data_backup_actions),
            ) {
                item {
                    EtaSegmentedCheckboxRow(
                        checked = includeAppData,
                        onCheckedChange = {
                            includeAppData = it
                            // 密钥藏在应用数据里，取消应用数据时一并复位，避免下次勾回来时意外带上。
                            if (!it) includeApiKeys = false
                        },
                        title = stringResource(R.string.data_backup_include_app_data),
                        summary = stringResource(R.string.data_backup_include_app_data_summary),
                        enabled = !busy,
                    )
                }
                item {
                    EtaSegmentedCheckboxRow(
                        // 未勾应用数据时包里必然没有密钥，显示也跟着是未勾。
                        checked = includeApiKeys && includeAppData,
                        onCheckedChange = { includeApiKeys = it },
                        title = stringResource(R.string.data_backup_include_api_keys),
                        summary = stringResource(R.string.data_backup_include_api_keys_summary),
                        enabled = !busy && includeAppData,
                    )
                }
                item {
                    EtaSegmentedCheckboxRow(
                        checked = includeLinuxEnvironment,
                        onCheckedChange = { includeLinuxEnvironment = it },
                        title = stringResource(R.string.data_backup_include_linux),
                        summary = buildString {
                            append(stringResource(R.string.data_backup_include_linux_summary))
                            linuxSizeLabel?.let { size ->
                                append('\n')
                                append(context.getString(R.string.data_backup_linux_size, size))
                            }
                        },
                        enabled = !busy,
                    )
                }
                item {
                    EtaSegmentedCheckboxRow(
                        checked = includeEmbeddings,
                        onCheckedChange = { includeEmbeddings = it },
                        title = stringResource(R.string.backup_media_include),
                        summary = buildString {
                            append(stringResource(R.string.backup_media_include_summary))
                            mediaSizeLabel?.let { size ->
                                append('\n')
                                append(context.getString(R.string.backup_media_size, size))
                            }
                        },
                        enabled = !busy,
                    )
                }
                item {
                    EtaSegmentedCheckboxRow(
                        checked = includeModels,
                        onCheckedChange = { includeModels = it },
                        title = stringResource(R.string.backup_models_include),
                        summary = buildString {
                            append(stringResource(R.string.backup_models_include_summary))
                            append('\n')
                            append(
                                if (modelSummary.modelCount > 0) {
                                    context.getString(
                                        R.string.backup_models_downloaded,
                                        modelSummary.modelCount,
                                        Formatter.formatShortFileSize(context, modelSummary.bytes),
                                    )
                                } else {
                                    context.getString(R.string.backup_models_none)
                                },
                            )
                        },
                        enabled = !busy && modelSummary.modelCount > 0,
                    )
                }
                item {
                    EtaSegmentedCheckboxRow(
                        checked = includeBrowserSettings,
                        onCheckedChange = { includeBrowserSettings = it },
                        title = stringResource(R.string.data_backup_include_browser),
                        summary = stringResource(R.string.data_backup_include_browser_summary),
                        enabled = !busy,
                    )
                }
            }
        }
        item(key = "recipes") {
            // 配方是模型自建的工具，只随「应用数据」一起进备份包；这里说明它的归属与去向，
            // 并提供去管理页查看 / 分享的指引。（系统备份已被 allowBackup=false 关掉，ZIP 是唯一载体。）
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
                title = stringResource(R.string.recipe_share_section),
            ) {
                item {
                    RecipeBackupRow(
                        count = recipeCount,
                        bytes = recipeBytes,
                    )
                }
            }
        }
        // WebDAV 云端备份：本地导出/导入之后，同一套备份包的云端通道。
        item(key = "webdav") {
            // 把页面上的勾选一并传下去：云端若固定用默认选项，同一份数据会比本地导出
            // 小一大截（Linux 环境、模型、浏览器设置都不进包）。
            WebDavBackupSection(
                exportOptions = EtaBackupExportOptions(
                    includeAppData = includeAppData,
                    includeApiKeys = includeApiKeys && includeAppData,
                    includeBrowserSettings = includeBrowserSettings,
                    includeLinuxEnvironment = includeLinuxEnvironment,
                    includeEmbeddings = includeEmbeddings,
                    includeModels = includeModels,
                ),
            )
        }
        item(key = "transfer") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
                title = stringResource(R.string.data_backup_transfer),
            ) {
                item {
                    BackupActionRow(
                        title = stringResource(R.string.data_backup_export),
                        summary = when {
                            exporting -> stringResource(R.string.data_backup_working)
                            !hasSelection -> stringResource(R.string.data_backup_nothing_selected)
                            else -> stringResource(R.string.data_backup_export_summary)
                        },
                        icon = Icons.Rounded.FileUpload,
                        loading = exporting,
                        enabled = !busy && hasSelection,
                        onClick = {
                            exportLauncher.launch(defaultBackupFileName(context))
                        },
                    )
                }
                item {
                    BackupActionRow(
                        title = stringResource(R.string.data_backup_import),
                        summary = if (importing || detecting) {
                            stringResource(R.string.data_backup_working)
                        } else {
                            stringResource(R.string.data_backup_import_summary)
                        },
                        icon = Icons.Rounded.FileDownload,
                        loading = importing || detecting,
                        enabled = !busy,
                        onClick = {
                            importLauncher.launch(arrayOf("application/zip", "application/json", "text/plain", "*/*"))
                        },
                    )
                }
                item {
                    BackupActionRow(
                        title = stringResource(R.string.conversation_analysis_import),
                        summary = if (importingAnalysis) {
                            stringResource(R.string.data_backup_working)
                        } else {
                            stringResource(R.string.conversation_analysis_import_summary)
                        },
                        icon = Icons.Rounded.QueryStats,
                        loading = importingAnalysis,
                        enabled = !busy,
                        onClick = {
                            analysisImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                    )
                }
            }
        }
    }

    val pending = pendingImport
    if (pending != null) {
        val modelsOnly = pending.kind == EtaBackupArchiveKind.MODELS
        EtaDialog(
            show = true,
            title = stringResource(
                if (modelsOnly) {
                    R.string.backup_models_import_confirm_title
                } else {
                    R.string.data_backup_import_confirm_title
                },
            ),
            summary = if (modelsOnly) {
                stringResource(R.string.backup_models_import_confirm_summary)
            } else {
                stringResource(R.string.data_backup_import_confirm_summary) + "\n" +
                    stringResource(R.string.data_backup_import_limits_summary) + "\n" +
                    stringResource(R.string.data_backup_single_conversation_note)
            },
            onDismissRequest = {
                if (!busy) pendingImport = null
            },
            actions = {
                EtaDialogActions(
                    confirmText = when {
                        busy -> stringResource(R.string.data_backup_working)
                        modelsOnly -> stringResource(R.string.backup_models_restore_action)
                        else -> stringResource(R.string.action_import)
                    },
                    destructive = !modelsOnly,
                    cancelEnabled = !busy,
                    confirmEnabled = !busy,
                    onCancel = { pendingImport = null },
                    onConfirm = {
                        val uri = pending.uri
                        val restoreModels = modelsOnly
                        pendingImport = null
                        scope.launch {
                            importing = true
                            try {
                                val input = context.contentResolver.openInputStream(uri)
                                    ?: error(context.getString(R.string.data_backup_file_open_failed))
                                if (restoreModels) {
                                    val summary = input.use { onImportModels(it) }
                                    modelSummary = EtaBackupRepository.downloadedModels(context)
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.backup_models_imported,
                                            summary.modelCount,
                                            Formatter.formatShortFileSize(context, summary.bytes),
                                        ),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    val summary = input.use { onImport(it) }
                                    modelSummary = EtaBackupRepository.downloadedModels(context)
                                    val base = backupMessage(
                                        context,
                                        context.getString(
                                            R.string.data_backup_imported,
                                            summary.conversationCount,
                                            summary.providerCount,
                                            summary.assistantCount,
                                        ),
                                        R.string.data_backup_imported_models,
                                        summary.downloadedModelCount,
                                    )
                                    // 脱敏过的备份要明确告诉用户少了什么，否则会以为导入不完整。
                                    val needsKeys = summary.providersNeedingKey > 0
                                    Toast.makeText(
                                        context,
                                        if (needsKeys) {
                                            "$base\n" + context.getString(
                                                R.string.data_backup_import_needs_keys,
                                                summary.providersNeedingKey,
                                            )
                                        } else {
                                            base
                                        },
                                        if (needsKeys) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            } catch (throwable: Throwable) {
                                showFailure(throwable)
                            } finally {
                                importing = false
                            }
                        }
                    },
                )
            },
        ) {
        }

        // 导入是纯 IO，可能持续数十秒（含图更久）：给出明确进度，且不允许中途取消。
        if (importingAnalysis) {
            WorkingDialog(
                title = stringResource(R.string.analysis_importing_title),
                message = stringResource(R.string.analysis_importing_message),
            )
        }

        // 导入完成后让用户选：沿用导出时的模型与助手，还是使用当前的选择。
        pendingImportedBindingId?.let { conversationId ->
            AlertDialog(
                onDismissRequest = onDismissImportedBinding,
                title = { M3Text(text = stringResource(R.string.import_binding_title)) },
                text = { M3Text(text = stringResource(R.string.import_binding_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val applied = onApplyImportedBinding(conversationId, true)
                                if (!applied) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.import_binding_unavailable),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        },
                    ) {
                        M3Text(text = stringResource(R.string.import_binding_keep))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            scope.launch { onApplyImportedBinding(conversationId, false) }
                        },
                    ) {
                        M3Text(text = stringResource(R.string.import_binding_current))
                    }
                },
            )
        }
    }
}

/** 待确认的导入：归档 URI + 探测出的归档类型（决定确认文案与走哪条导入链）。 */
private data class PendingImport(
    val uri: Uri,
    val kind: EtaBackupArchiveKind,
)

/** 纯提示行：直接以文字排在页面背景上（不带容器）。 */
@Composable
private fun BackupNoticeRow(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        M3Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        M3Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 备份/导入入口行。
 *
 * 走官方分段行 [EtaSegmentedRow]（自带涟漪与语义），加载态经 [EtaLoadingSlot] 统一封装。
 */
@Composable
internal fun BackupActionRow(
    title: String,
    summary: String,
    icon: ImageVector,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    EtaSegmentedRow(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        leadingContent = {
            if (loading) {
                EtaLoadingSlot(
                    progress = null,
                    size = EtaDimens.RowLeadingLoadingSize,
                )
            } else {
                // 着色走共享 PreferenceIcon：启用 onSurfaceVariant、禁用官方禁用档
                // （onSurface × 0.38），与设置页行首图标同一套状态配色。
                PreferenceIcon(icon = icon, enabled = enabled)
            }
        },
        content = { M3Text(text = title) },
        supportingContent = {
            M3Text(text = summary)
        },
    )
}

private fun defaultBackupFileName(context: Context): String =
    context.getString(R.string.data_backup_file_name, SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()))

/** 备份页里的配方说明行：数量 + 体积 + 指向管理页的提示。 */
@Composable
private fun RecipeBackupRow(
    count: Int,
    bytes: Long,
) {
    EtaSegmentedRow(
        modifier = Modifier.fillMaxWidth(),
        leadingContent = {
            Icon(
                imageVector = Icons.Rounded.Handyman,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        content = { M3Text(text = stringResource(R.string.data_backup_recipes, count)) },
        supportingContent = {
            M3Text(
                text = if (count == 0) {
                    stringResource(R.string.recipe_empty_summary)
                } else {
                    stringResource(
                        R.string.data_backup_recipes_size,
                        Formatter.formatShortFileSize(LocalContext.current, bytes),
                    )
                },
            )
        },
    )
}

private fun backupMessage(context: Context, base: String, modelCountRes: Int, modelCount: Int): String =
    if (modelCount > 0) base + "\n" + context.getString(modelCountRes, modelCount) else base

/** 分析 JSON 的硬上限：读进内存的是 UTF-16 字符串，实际占用约两倍于此。 */
private const val MAX_ANALYSIS_FILE_BYTES = 64 * 1024 * 1024L
