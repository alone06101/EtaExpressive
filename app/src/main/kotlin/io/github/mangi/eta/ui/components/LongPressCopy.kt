package io.github.mangi.eta.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 长按手势：只观察，不消费。
 *
 * 消息气泡内部有链接、代码块复制按钮、可点击的卡片；用 combinedClickable 包一层会把
 * 这些子元素的点击吞掉。这里用 awaitEachGesture 自己等超时：
 * - requireUnconsumed = false：子元素已经处理过的按下也能看到；
 * - 全程不调用 consume()，子元素的点击与列表滚动照常；
 * - 抬起早于长按阈值就什么也不做。
 */
internal fun Modifier.onLongPressOnly(onLongPress: () -> Unit): Modifier = pointerInput(onLongPress) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val upBeforeTimeout = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.any { it.changedToUp() }) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        if (upBeforeTimeout == null) onLongPress()
    }
}
