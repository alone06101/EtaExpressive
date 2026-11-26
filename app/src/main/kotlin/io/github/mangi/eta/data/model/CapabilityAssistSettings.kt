package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * 能力协助（跨提供商接管）设置。
 *
 * 首选模型缺少本轮需要的能力（图片需要视觉、视频需要视频输入）时，把这一轮整条请求
 * 临时交给另一个支持该能力的模型，跑完不留痕：下一轮仍按用户选定模型发起。
 *
 * [providerOrder] 是允许参与协助的提供商顺序，靠前的优先；为空表示「全部启用的提供商，
 * 按现有顺序」。用户插进列表里的提供商如果被停用或没有满足能力的模型，会在挑选时跳过。
 */
@Serializable
internal data class CapabilityAssistSettings(
    val enabled: Boolean = true,
    val providerOrder: List<String> = emptyList(),
)
