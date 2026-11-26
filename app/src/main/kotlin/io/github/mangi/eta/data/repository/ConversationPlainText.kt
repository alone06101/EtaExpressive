package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把会话拼成可直接贴到别处的纯文本。
 *
 * 只收 user / assistant 两类消息：thinking 与 tool 是过程性内容，复制出去对用户
 * 没有价值，也是长度失控的主要来源。不加任何 Markdown 装饰——复制是给「粘贴到别处」
 * 用的，标题符号在目标环境里通常只是噪声。
 */
internal object ConversationPlainText {
    /** 单条消息的说话人前缀；与导出文本、界面语言无关，保持稳定便于二次处理。 */
    private const val USER_LABEL = "我："
    private const val ASSISTANT_LABEL = "Eta："

    /** 复制上限：超过这个长度按字符截断，避免剪贴板塞入几 MB 文本导致目标应用卡死。 */
    const val MAX_CHARS = 200_000

    data class Result(
        val text: String,
        val truncated: Boolean,
        val messageCount: Int,
    )

    suspend fun build(context: Context, conversationId: String): Result = withContext(Dispatchers.IO) {
        val title = runCatching {
            EtaDatabase.get(context).conversationDao().conversationMetadata(conversationId)?.title
        }.getOrNull().orEmpty()
        val rows = EtaDatabase.get(context).conversationDao().copyableMessages(conversationId)
        val builder = StringBuilder()
        if (title.isNotBlank()) builder.append(title.trim()).append("\n\n")
        var truncated = false
        var used = 0
        for (row in rows) {
            val body = row.content.trim()
            if (body.isEmpty()) continue
            val label = if (row.type == "user") USER_LABEL else ASSISTANT_LABEL
            val block = label + body + "\n\n"
            if (used + block.length > MAX_CHARS) {
                val room = (MAX_CHARS - used).coerceAtLeast(0)
                if (room > 0) builder.append(block, 0, room)
                truncated = true
                break
            }
            builder.append(block)
            used += block.length
        }
        Result(
            text = builder.toString().trimEnd(),
            truncated = truncated,
            messageCount = rows.size,
        )
    }
}
