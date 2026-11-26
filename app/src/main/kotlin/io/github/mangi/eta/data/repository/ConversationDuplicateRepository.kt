package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 克隆一个会话。
 *
 * 复用「导出会话分析 JSON → 导入为新会话」这条现成链路，而不是手写一遍
 * conversations / conversation_messages / conversation_context_checkpoints 的插入：
 * 那条链路已经处理好消息 id 重映射、run id 重映射、工具历史重建与附件引用，
 * 自己再实现一遍只会多出一处容易漏字段的地方。
 *
 * 导入端是给「跨设备迁移」用的，会故意把 provider/model/assistant 留空、
 * folder 置空（目标设备未必有同样的配置）。同机副本不需要这个语义，
 * 所以这里再把这几项按源会话还原，并给标题加上副本后缀。
 */
internal object ConversationDuplicateRepository {
    suspend fun duplicate(
        context: Context,
        conversationId: String,
        titleSuffix: String,
    ): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val dao = EtaDatabase.get(app).conversationDao()
        val source = dao.conversationEntity(conversationId)
            ?: throw IllegalStateException("会话不存在")

        // 带上图片：副本要能完整重看原对话，图片丢了等于残本。
        val document = EtaConversationAnalysisRepository.export(
            app,
            conversationId,
            EtaConversationAnalysisOptions(includeImages = true),
        )
        val newId = EtaConversationAnalysisRepository.import(app, document)

        // 对副本再克隆时去掉已有的副本后缀，避免「X（副本）（副本）」。
        val baseTitle = source.title.removeSuffix(titleSuffix).trim().ifBlank { "未命名" }
        dao.updateCloneMetadata(
            id = newId,
            title = baseTitle + titleSuffix,
            providerId = source.providerId,
            modelId = source.modelId,
            assistantId = source.assistantId,
            folderId = source.folderId,
            updatedAt = System.currentTimeMillis(),
        )

        // 模型历史优先读独立文件（见 AgentConversationHistoryFiles），只写 checkpoint
        // 会让副本在「继续对话」时丢掉工具角色那一部分上下文。
        runCatching {
            val sourceFile = AgentConversationHistoryFiles.file(app, conversationId)
            if (sourceFile.isFile) {
                val target = AgentConversationHistoryFiles.file(app, newId)
                target.parentFile?.mkdirs()
                sourceFile.copyTo(target, overwrite = true)
            }
        }

        newId
    }
}
