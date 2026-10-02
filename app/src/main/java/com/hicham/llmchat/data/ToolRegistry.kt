package com.hicham.llmchat.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Model-facing tool descriptions only.
 *
 * ToolRegistry deliberately has no effectful execution method. A tool call from a model
 * must cross RuntimeToolGateway -> AgentRuntime -> CapabilityExecutor.
 */
object ToolRegistry {
    fun toolDefinitions(): List<JSONObject> = listOf(
        JSONObject().apply {
            put("name", "get_current_time")
            put("description", "Get the current local date and time on the user's device.")
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
                put("required", JSONArray())
            })
        },
        JSONObject().apply {
            put("name", "calculate")
            put("description", "Evaluate a basic arithmetic expression (+ - * / parentheses) and return the numeric result.")
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("expression", JSONObject().apply {
                        put("type", "string")
                        put("description", "e.g. \"12 * (3 + 4)\"")
                    })
                })
                put("required", JSONArray().put("expression"))
            })
        },
        JSONObject().apply {
            put("name", "remember")
            put("description", "Persist a short private user fact locally on the device using a stable key.")
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("key", JSONObject().apply {
                        put("type", "string")
                        put("description", "Stable fact name, e.g. preferred_language")
                    })
                    put("value", JSONObject().apply {
                        put("type", "string")
                        put("description", "Short fact value to remember")
                    })
                })
                put("required", JSONArray().put("key").put("value"))
            })
        }
    )
}
