package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 能力协助路由：首选模型缺少本轮所需能力时，临时换一个能处理它的模型。
 *
 * 只看「本轮请求真的要送什么」：没有图片/视频就完全不介入，避免把普通对话
 * 悄悄换成别的模型。换来的配置只用于这一条请求——请求体里带着 config
 * （见 AgentRuntimeWire），下一轮仍从用户选定的模型重新构建，不需要恢复动作。
 *
 * 刻意不做的事：
 * - 首选自己没配凭据时不接管。那是配置问题，替它掩盖只会把「没配好」
 *   伪装成「模型能力不足」，用户更难定位。
 * - 不接管工具能力。工具缺失是意图/配置层面的选择，不是模态能力缺口。
 */
internal object CapabilityAssistRouter {
    /**
     * @param needsImage 本轮要送图片（需要视觉）
     * @param needsVideo 本轮要送视频（需要视频输入）
     * @return 可用配置；没有任何候选满足时原样返回 [config]
     */
    suspend fun route(
        config: AgentModelClient.ModelConfig,
        needsImage: Boolean,
        needsVideo: Boolean,
    ): AgentModelClient.ModelConfig = withContext(Dispatchers.IO) {
        if (!needsImage && !needsVideo) return@withContext config
        val settings = runCatching { SettingsDataStore.capabilityAssist() }.getOrNull()
            ?: return@withContext config
        if (!settings.enabled) return@withContext config
        // 首选本来就支持，不需要协作。
        val visionCovered = !needsImage || config.supportsVision
        val videoCovered = !needsVideo || config.supportsVideo
        if (visionCovered && videoCovered) return@withContext config
        if (!config.hasCredentials()) {
            AndroidAgentLogger.warn("capability assist skipped: primary provider has no credentials")
            return@withContext config
        }

        val candidates = settings.providerOrder.ifEmpty { enabledProviderIds() }
        for (providerId in candidates) {
            if (providerId == config.providerId) continue
            val provider = runCatching { ProviderRepository.providerById(providerId) }
                .getOrNull()?.takeIf { it.isEnabled } ?: continue
            val model = provider.models
                .filter { it.isEnabled }
                .sortedBy { it.sortOrder }
                .firstOrNull { candidate ->
                    (!needsImage || candidate.supportsVision) && (!needsVideo || candidate.supportsVideo)
                } ?: continue
            val assist = runCatching {
                RuntimeConfigRepository.configForProviderAndModel(providerId, model.id, assistant = null)
            }.getOrNull() ?: continue
            AndroidAgentLogger.debug {
                "capability assist: ${config.model} -> ${assist.model} (image=$needsImage video=$needsVideo)"
            }
            val requested = config.effectiveReasoningEffort
            val capabilities = assist.reasoningCapabilities
            val assistedEffort = if (requested == ReasoningEffort.OFF || capabilities == null) {
                ReasoningEffort.OFF
            } else {
                capabilities.normalize(requested)
            }
            return@withContext assist.copy(
                // 会话与运行层面的选择必须跟着主业走，只有「跟哪家模型说话」被替换。
                systemPrompt = config.systemPrompt,
                assistantId = config.assistantId,
                terminalTools = config.terminalTools,
                browserTools = config.browserTools,
                screenTools = config.screenTools,
                deviceTools = config.deviceTools,
                personalTools = config.personalTools,
                errorReconnectPolicy = config.errorReconnectPolicy,
                // 推理强度按新模型自己的能力归一化：原档位它可能根本不支持。
                // 不复用 UI 层的 ConversationReasoningPolicy——data 层不反向依赖 ui。
                reasoningEffort = assistedEffort,
                thinkingEnabled = assistedEffort.enablesReasoning,
                // 新模型的上下文更小时，按它的真实窗口收紧，避免开局就超限。
                contextWindow = assist.contextWindow,
            )
        }
        config
    }

    /**
     * 为「本轮要出图」挑一个图像生成模型。
     *
     * 与输入能力路由分开：输入能力换的是「谁来看图」，这里换的是「谁来画图」，
     * 判定的是输出能力（supportsImageGeneration）。顺序同样取协助设置的 providerOrder，
     * 为空时按现有提供商顺序。
     *
     * @return 命中的提供商与模型；首选自己就能出图、或没有任何候选时返回 null
     */
    suspend fun pickImageModel(
        excludedProviderId: String?,
    ): Pair<ProviderSetting, Model>? = withContext(Dispatchers.IO) {
        val settings = runCatching { SettingsDataStore.capabilityAssist() }.getOrNull() ?: return@withContext null
        if (!settings.enabled) return@withContext null
        val candidates = settings.providerOrder.ifEmpty { enabledProviderIds() }
        for (providerId in candidates) {
            if (providerId == excludedProviderId) continue
            val provider = runCatching { ProviderRepository.providerById(providerId) }
                .getOrNull()?.takeIf { it.isEnabled } ?: continue
            val model = provider.models
                .filter { it.isEnabled && it.supportsImageGeneration && !it.supportsVideoGeneration }
                .sortedBy { it.sortOrder }
                .firstOrNull() ?: continue
            AndroidAgentLogger.debug {
                "capability assist (image output): ${provider.id}/${model.modelId}"
            }
            return@withContext provider to model
        }
        null
    }

    /**
     * 给非 suspend 的发送路径用的同步入口。
     *
     * 只有「用户明确要出图、而当前模型不能出图」时才会被调用，属于低频路径：
     * 普通消息在调用前就被 ImageIntent 挡掉了，不会付出阻塞代价。
     * 读的是本地 DataStore + provider 表，量级很小。
     */
    fun pickImageModelBlocking(excludedProviderId: String?): Pair<ProviderSetting, Model>? =
        runCatching {
            kotlinx.coroutines.runBlocking { pickImageModel(excludedProviderId) }
        }.getOrNull()

    private suspend fun enabledProviderIds(): List<String> =
        runCatching {
            ProviderRepository.allProviders().filter { it.isEnabled }.map { it.id }
        }.getOrDefault(emptyList())

    /** 密钥缺失的提供商不该被选中：它会直接失败，而不是「能力不足」。 */
    private fun AgentModelClient.ModelConfig.hasCredentials(): Boolean = when (authMode) {
        ProviderAuthModes.API_KEY -> apiKey.isNotBlank()
        else -> true
    }
}
