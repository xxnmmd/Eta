package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentToolSchema {
    fun coordinateSpace(): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("enum", JSONArray().put("screenshot").put("screen"))
            .put(
                "description",
                "screenshot 表示最近一次 observe_screen 附图的像素坐标；screen 表示真实设备屏幕坐标。默认 screenshot。",
            )

    /** 目标屏幕；默认 0 表示主屏，其他屏幕需要 root 与系统侧创建的虚拟屏。 */
    fun displayId(): JSONObject =
        JSONObject()
            .put("type", "integer")
            .put("minimum", 0)
            .put(
                "description",
                "目标屏幕 ID，默认 0（主屏）。虚拟屏/副屏可先用 terminal 通过 root 创建，可用屏幕见 observe_screen 的 displays。",
            )

    fun function(
        name: String,
        description: String,
        parameters: JSONObject,
    ): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
}
