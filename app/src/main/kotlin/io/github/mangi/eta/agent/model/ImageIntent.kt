package io.github.mangi.eta.agent.model

/**
 * 「这轮是不是要出图」的保守判定。
 *
 * 只在**当前模型本身不能生成图像**时才被调用（能出图的模型根本不需要判断），
 * 所以这里宁漏不误：漏判的后果是用户重说一句，误判的后果是整轮跑错通道、
 * 还把一段本该是文字的回答变成一张图。
 *
 * 因此白名单收得窄（明确的「画一个 / 生成图片」这类祈使表达），黑名单先挡掉
 * 疑问式与「画法 / 示意图」这类其实在问方法或要结构图的说法。
 */
internal object ImageIntent {
    private val DRAW_PATTERNS = listOf(
        "画一个", "画一只", "画一幅", "画一张", "画个", "画张", "画幅",
        "生成一张图", "生成一张图片", "生成图片", "生成图像",
        "来一张图", "来张图", "做一张图", "出一张图",
    )

    private val NEGATIVE_PATTERNS = listOf(
        // 在问方法，而不是要图
        "怎么画", "如何画", "画法", "怎么生成", "如何生成",
        // 这些「图」通常要文字/结构化结果，不是位图
        "流程图", "示意图", "结构图", "架构图", "表格", "思维导图",
        // 在评价已有内容
        "这幅画", "这张图", "这个图",
    )

    fun wantsImage(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (NEGATIVE_PATTERNS.any { it in trimmed }) return false
        return DRAW_PATTERNS.any { it in trimmed }
    }
}
