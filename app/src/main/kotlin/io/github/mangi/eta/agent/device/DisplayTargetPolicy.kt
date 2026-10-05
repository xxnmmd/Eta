package io.github.mangi.eta.agent.device

/**
 * 屏幕定位的纯策略：把可选 display_id 归一化，并决定手势走无障碍还是 root。
 *
 * 主屏（0）沿用无障碍手势与逐窗口截图；其他屏幕没有无障碍手势通道，
 * 只能通过 root 的 `input -d <displayId>` 驱动，因此坐标校验也必须按目标屏幕尺寸进行。
 */
internal object DisplayTargetPolicy {
    const val DEFAULT_DISPLAY_ID = 0

    fun normalize(displayId: Int?): Int {
        val requested = displayId ?: return DEFAULT_DISPLAY_ID
        return if (requested >= DEFAULT_DISPLAY_ID) requested else DEFAULT_DISPLAY_ID
    }

    fun usesRootInput(displayId: Int): Boolean = displayId != DEFAULT_DISPLAY_ID

    fun pointWithin(x: Int, y: Int, width: Int, height: Int): Boolean =
        width > 0 && height > 0 && x in 0 until width && y in 0 until height
}
