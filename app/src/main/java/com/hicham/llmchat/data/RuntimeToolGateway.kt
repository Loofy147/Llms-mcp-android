package com.hicham.llmchat.data

import com.hicham.llmchat.runtime.ActivationRequest
import com.hicham.llmchat.runtime.ActivationSource
import com.hicham.llmchat.runtime.AgentRuntime
import com.hicham.llmchat.runtime.NativeActions
import com.hicham.llmchat.runtime.RunStatus

/**
 * Provider-facing adapter that translates model tool calls into canonical runtime Activations.
 * It contains no effect implementation of its own.
 */
class RuntimeToolGateway(private val runtime: AgentRuntime) {
    fun execute(name: String, inputJson: String, identity: String = "local-user"): String {
        val actionId = when (name) {
            "get_current_time" -> NativeActions.CURRENT_TIME
            "calculate" -> NativeActions.CALCULATE
            else -> return "Error: unknown tool '$name'"
        }

        return try {
            val input = parseStringMap(inputJson)
            val run = runtime.activate(
                ActivationRequest(
                    source = ActivationSource.MODEL,
                    actionId = actionId,
                    input = input,
                    identity = identity
                )
            )
            when (run.status) {
                RunStatus.SUCCEEDED -> run.output["value"] ?: run.output["result"] ?: ""
                RunStatus.DENIED,
                RunStatus.WAITING_APPROVAL,
                RunStatus.FAILED,
                RunStatus.CANCELLED -> "Error: ${run.denialReason ?: run.status.name}"
                RunStatus.CREATED,
                RunStatus.RUNNING -> "Error: runtime did not reach a terminal state"
            }
        } catch (e: IllegalArgumentException) {
            "Error: ${e.message ?: "invalid tool input"}"
        } catch (e: Exception) {
            "Error: ${e.message ?: "tool execution failed"}"
        }
    }

    /**
     * Minimal dependency-free parser for model tool argument objects.
     * JSON object values are normalized to strings, matching the runtime's input contract.
     */
    private fun parseStringMap(inputJson: String): Map<String, String> {
        val parser = StringMapJsonParser(inputJson)
        return parser.parse()
    }

    private class StringMapJsonParser(private val source: String) {
        private var index = 0

        fun parse(): Map<String, String> {
            skipWhitespace()
            expect('{')
            skipWhitespace()
            if (peek() == '}') {
                index++
                skipWhitespace()
                require(index == source.length) { "Unexpected trailing input" }
                return emptyMap()
            }

            val result = linkedMapOf<String, String>()
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = parseValueAsString()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        skipWhitespace()
                        require(index == source.length) { "Unexpected trailing input" }
                        return result
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at position $index")
                }
            }
        }

        private fun parseValueAsString(): String {
            return when (peek()) {
                '"' -> parseString()
                else -> {
                    val start = index
                    while (index < source.length && source[index] != ',' && source[index] != '}') index++
                    source.substring(start, index).trim().also {
                        require(it.isNotEmpty()) { "Expected value at position $start" }
                    }
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < source.length) {
                when (val ch = source[index++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(index < source.length) { "Incomplete escape sequence" }
                        when (val escaped = source[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(index + 4 <= source.length) { "Incomplete unicode escape" }
                                val hex = source.substring(index, index + 4)
                                out.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("Unsupported escape '\\$escaped'")
                        }
                    }
                    else -> {
                        require(ch >= ' ') { "Control character in JSON string" }
                        out.append(ch)
                    }
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        private fun expect(expected: Char) {
            require(index < source.length && source[index] == expected) {
                "Expected '$expected' at position $index"
            }
            index++
        }

        private fun peek(): Char? = source.getOrNull(index)
    }
}
