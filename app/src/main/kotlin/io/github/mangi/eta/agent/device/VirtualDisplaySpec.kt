package io.github.mangi.eta.agent.device

import org.json.JSONObject

/**
 * 模拟副屏（开发者选项的 `overlay_display_devices`）参数策略。
 *
 * 这是普通应用在只有 Root 的情况下唯一能创建的虚拟屏幕：系统会为它建立真实的
 * DisplayManager 显示，因此可以被无障碍服务观察、截图，并由 `input -d` 驱动。
 * 它同时会以浮窗形式叠加在主屏上，所以不是"完全离屏"；真正离屏需要系统签名权限。
 */
internal object VirtualDisplaySpec {
    const val DEFAULT_WIDTH = 720
    const val DEFAULT_HEIGHT = 1280
    const val DEFAULT_DENSITY = 320

    const val MIN_SIZE = 200
    const val MAX_WIDTH = 2_400
    const val MAX_HEIGHT = 2_400
    const val MIN_DENSITY = 120
    const val MAX_DENSITY = 640

    data class Spec(
        val width: Int,
        val height: Int,
        val density: Int,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("width", width)
            .put("height", height)
            .put("density", density)
    }

    fun normalize(width: Int?, height: Int?, density: Int?): Spec = Spec(
        width = (width ?: DEFAULT_WIDTH).coerceIn(MIN_SIZE, MAX_WIDTH),
        height = (height ?: DEFAULT_HEIGHT).coerceIn(MIN_SIZE, MAX_HEIGHT),
        density = (density ?: DEFAULT_DENSITY).coerceIn(MIN_DENSITY, MAX_DENSITY),
    )

    fun settingsValue(spec: Spec): String = "${spec.width}x${spec.height}/${spec.density}"

    /** 只解析形如 `720x1280/320` 的条目；其他系统关键字原样忽略。 */
    fun parse(value: String?): List<Spec> = value.orEmpty()
        .split(',')
        .mapNotNull { entry -> ENTRY.matchEntire(entry.trim())?.let { match ->
            val width = match.groupValues[1].toIntOrNull() ?: return@let null
            val height = match.groupValues[2].toIntOrNull() ?: return@let null
            val density = match.groupValues[3].toIntOrNull() ?: return@let null
            Spec(width = width, height = height, density = density)
        } }

    private val ENTRY = Regex("""(\d{2,5})x(\d{2,5})/(\d{2,4})""")
}
