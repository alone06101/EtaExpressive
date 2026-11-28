package io.github.mangi.eta.ui.components

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import kotlinx.coroutines.launch

/**
 * 导入完成后的绑定选择：「沿用导出时的」还是「使用当前的」。
 *
 * 放在全局（AgentAppRoot）而不是备份页里：导入有两个入口——备份页导入、
 * 空白会话长按导入。弹窗若只挂在备份页，从会话列表导入时永远不会出现，
 * 用户会以为漏掉了什么。
 */
@Composable
internal fun ImportedBindingDialog(
    conversationId: String,
    onDismiss: () -> Unit,
    onApply: suspend (String, Boolean) -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.import_binding_title)) },
        text = { Text(text = stringResource(R.string.import_binding_message)) },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        val applied = onApply(conversationId, true)
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
                Text(text = stringResource(R.string.import_binding_keep))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { scope.launch { onApply(conversationId, false) } },
            ) {
                Text(text = stringResource(R.string.import_binding_current))
            }
        },
    )
}
