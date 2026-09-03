package com.hicham.llmchat.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Native, client-side tools the app itself can execute. These are declared
 * in the "tools" array of every request; when the model calls one, it runs
 * locally in Kotlin. This is distinct from MCP tools, which the Anthropic
 * API executes server-side against a remote MCP server (see AnthropicClient).
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
        }
    )

    /** Executes a tool call by name and returns the text to send back as a tool_result. */
    fun execute(name: String, inputJson: String): String {
        return try {
            val input = if (inputJson.isBlank()) JSONObject() else JSONObject(inputJson)
            when (name) {
                "get_current_time" -> {
                    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss (zzz)", Locale.getDefault())
                    fmt.format(Date())
                }
                "calculate" -> SafeArithmetic.evaluate(input.optString("expression", "")).toString()
                else -> "Error: unknown tool '$name'"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

/**
 * A tiny recursive-descent arithmetic evaluator. Deliberately NOT using
 * eval() or a scripting engine, since that would mean executing
 * model-provided strings as arbitrary code. Supports + - * / ( ) and
 * decimals only.
 */
object SafeArithmetic {
    fun evaluate(expr: String): Double {
        if (!expr.all { it.isDigit() || it in "+-*/(). " }) {
            throw IllegalArgumentException("Expression contains characters other than digits, + - * / ( ) and spaces")
        }
        val parser = Parser(expr)
        val result = parser.parseExpression()
        parser.expectEnd()
        return result
    }

    private class Parser(val s: String) {
        var pos = 0
        fun peek(): Char? = if (pos < s.length) s[pos] else null
        fun skipSpace() { while (peek() == ' ') pos++ }

        fun parseExpression(): Double {
            skipSpace()
            var value = parseTerm()
            while (true) {
                skipSpace()
                when (peek()) {
                    '+' -> { pos++; value += parseTerm() }
                    '-' -> { pos++; value -= parseTerm() }
                    else -> return value
                }
            }
        }

        fun parseTerm(): Double {
            skipSpace()
            var value = parseFactor()
            while (true) {
                skipSpace()
                when (peek()) {
                    '*' -> { pos++; value *= parseFactor() }
                    '/' -> { pos++; value /= parseFactor() }
                    else -> return value
                }
            }
        }

        fun parseFactor(): Double {
            skipSpace()
            if (peek() == '-') { pos++; return -parseFactor() }
            if (peek() == '(') {
                pos++
                val v = parseExpression()
                skipSpace()
                if (peek() != ')') throw IllegalArgumentException("Expected ')'")
                pos++
                return v
            }
            val start = pos
            while (peek() != null && (peek()!!.isDigit() || peek() == '.')) pos++
            if (start == pos) throw IllegalArgumentException("Expected number at position $pos")
            return s.substring(start, pos).toDouble()
        }

        fun expectEnd() {
            skipSpace()
            if (pos != s.length) throw IllegalArgumentException("Unexpected trailing input")
        }
    }
}
