package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.CapabilityAssistSettings
import io.github.mangi.eta.data.model.ProviderSetting

/**
 * 能力协助设置：开关 + 参与顺序。
 *
 * 顺序即优先级：靠上的提供商先被尝试。未勾选的提供商不参与协助，但仍然可以
 * 作为普通提供商手动选用——这里只决定「自动接管」时找谁。
 *
 * 保存时把「勾选集合」按当前显示顺序压成 providerOrder：顺序信息只存在于
 * 列表里，避免再存一份「全部顺序 + 参与集合」造成两者不一致。
 */
@Composable
internal fun CapabilityAssistDialog(
    providers: List<ProviderSetting>,
    settings: CapabilityAssistSettings,
    onDismiss: () -> Unit,
    onSave: (CapabilityAssistSettings) -> Unit,
) {
    var enabled = remember { mutableStateOf(settings.enabled) }
    // 已知提供商按已保存顺序排在前面，后加入的按现有顺序补在后面。
    val ordered = remember(providers, settings) {
        mutableStateListOf<String>().apply {
            val byId = providers.associateBy { it.id }
            settings.providerOrder.mapNotNull { byId[it]?.id }.forEach(::add)
            providers.forEach { provider -> if (provider.id !in this) add(provider.id) }
        }
    }
    val participants = remember(providers, settings) {
        mutableStateListOf<String>().apply {
            if (settings.providerOrder.isEmpty()) {
                providers.forEach { add(it.id) }
            } else {
                addAll(settings.providerOrder.filter { id -> providers.any { it.id == id } })
            }
        }
    }
    val nameById = remember(providers) { providers.associate { it.id to it.name } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.capability_assist_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.capability_assist_enable),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Switch(
                        checked = enabled.value,
                        onCheckedChange = { enabled.value = it },
                    )
                }
                Text(
                    text = stringResource(R.string.capability_assist_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                if (ordered.isEmpty()) {
                    Text(
                        text = stringResource(R.string.capability_assist_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(ordered, key = { it }) { providerId ->
                            val checked = providerId in participants
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { next ->
                                        if (next) {
                                            if (providerId !in participants) participants.add(providerId)
                                        } else {
                                            participants.remove(providerId)
                                        }
                                    },
                                )
                                Text(
                                    text = nameById[providerId] ?: providerId,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                val index = ordered.indexOf(providerId)
                                IconButton(
                                    enabled = index > 0,
                                    onClick = {
                                        if (index > 0) {
                                            val moved = ordered.removeAt(index)
                                            ordered.add(index - 1, moved)
                                        }
                                    },
                                ) {
                                    Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.capability_assist_move_up))
                                }
                                IconButton(
                                    enabled = index < ordered.lastIndex,
                                    onClick = {
                                        if (index in 0 until ordered.lastIndex) {
                                            val moved = ordered.removeAt(index)
                                            ordered.add(index + 1, moved)
                                        }
                                    },
                                ) {
                                    Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.capability_assist_move_down))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        settings.copy(
                            enabled = enabled.value,
                            providerOrder = ordered.filter { it in participants },
                        ),
                    )
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
