package io.github.mangi.eta.agent.model

/**
 * 「这轮是不是要出视频」的保守判定。
 *
 * 与 [ImageIntent] 同一套取舍：只在当前模型不能生成视频时才调用，
 * 漏判只是重说一句，误判会把整轮跑到视频通道上（成本比出图高得多）。
 */
internal object VideoIntent {
    private val GENERATE_PATTERNS = listOf(
        "生成一段视频", "生成一段短片", "生成视频", "生成短片",
        "做一段视频", "做一个视频", "做个视频", "做段视频",
        "来一段视频", "来段视频", "出一段视频", "出段视频",
        "生成一段动画", "生成动画视频",
    )

    private val NEGATIVE_PATTERNS = listOf(
        "怎么生成视频", "如何生成视频", "视频怎么拍", "视频生成原理",
        "视频画质", "视频格式", "视频编码", "视频剪辑",
    )

    fun wantsVideo(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (NEGATIVE_PATTERNS.any { it in trimmed }) return false
        return GENERATE_PATTERNS.any { it in trimmed }
    }
}
