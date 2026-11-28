package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * 阻塞式进度提示。
 *
 * 导入/导出分析 JSON 是纯 IO 操作，可能持续数秒到数十秒（含图时更久）。
 * 之前只有按钮内的 loading 或干脆没有任何反馈，用户会以为点击没生效。
 *
 * 刻意不可取消：这些操作已经开始写库或写文件，中途取消只会留下半成品。
 */
@Composable
internal fun WorkingDialog(
    title: String,
    message: String,
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(text = title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
    )
}
