package com.hicham.llmchat.runtime

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Canonical Actions for the small set of built-in local tools exposed to the model. */
object NativeActions {
    const val CURRENT_TIME = "native.current_time"
    const val CALCULATE = "native.calculate"

    fun catalog(): List<ActionDefinition> = listOf(currentTime(), calculate())

    private fun currentTime() = ActionDefinition(
        id = CURRENT_TIME,
        version = 1,
        purpose = "Read the current local date and time from the device.",
        capabilities = listOf(
            CapabilityDescriptor("device.time.read", EffectClass.READ_ONLY)
        ),
        reduce = { _, results ->
            val result = results.single()
            ActionExecution(output = result.output, observations = result.observations)
        },
        plan = { ActionPlan(listOf(CapabilityInvocationSpec("device.time.read"))) }
    )

    private fun calculate() = ActionDefinition(
        id = CALCULATE,
        version = 1,
        purpose = "Evaluate a bounded arithmetic expression without executing arbitrary code.",
        capabilities = listOf(
            CapabilityDescriptor("device.calculator.evaluate", EffectClass.READ_ONLY)
        ),
        reduce = { input, results ->
            val result = results.single()
            ActionExecution(
                output = result.output,
                observations = result.observations + Observation("expression", input["expression"].orEmpty())
            )
        },
        plan = { input ->
            ActionPlan(
                listOf(
                    CapabilityInvocationSpec(
                        capabilityId = "device.calculator.evaluate",
                        parameters = mapOf("expression" to input["expression"].orEmpty())
                    )
                )
            )
        }
    )

    fun timeNow(): CapabilityExecution {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss (zzz)", Locale.getDefault())
        return CapabilityExecution(
            output = mapOf("value" to fmt.format(Date())),
            observations = listOf(Observation("local_time", fmt.format(Date())))
        )
    }
}

/**
 * No scripting/eval facility is used. Only digits, decimal points, arithmetic operators,
 * parentheses, unary minus, and spaces are accepted.
 */
object SafeArithmetic {
    fun evaluate(expr: String): Double {
        if (!expr.all { it.isDigit() || it in "+-*/(). " }) {
            throw IllegalArgumentException("Expression contains unsupported characters")
        }
        val parser = Parser(expr)
        val result = parser.parseExpression()
        parser.expectEnd()
        return result
    }

    private class Parser(private val source: String) {
        private var pos = 0

        private fun peek(): Char? = source.getOrNull(pos)
        private fun skipSpaces() { while (peek() == ' ') pos++ }

        fun parseExpression(): Double {
            skipSpaces()
            var value = parseTerm()
            while (true) {
                skipSpaces()
                when (peek()) {
                    '+' -> { pos++; value += parseTerm() }
                    '-' -> { pos++; value -= parseTerm() }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            skipSpaces()
            var value = parseFactor()
            while (true) {
                skipSpaces()
                when (peek()) {
                    '*' -> { pos++; value *= parseFactor() }
                    '/' -> { pos++; value /= parseFactor() }
                    else -> return value
                }
            }
        }

        private fun parseFactor(): Double {
            skipSpaces()
            if (peek() == '-') { pos++; return -parseFactor() }
            if (peek() == '(') {
                pos++
                val value = parseExpression()
                skipSpaces()
                if (peek() != ')') throw IllegalArgumentException("Expected ')'")
                pos++
                return value
            }
            val start = pos
            while (peek()?.let { it.isDigit() || it == '.' } == true) pos++
            if (start == pos) throw IllegalArgumentException("Expected number at position $pos")
            return source.substring(start, pos).toDouble()
        }

        fun expectEnd() {
            skipSpaces()
            if (pos != source.length) throw IllegalArgumentException("Unexpected trailing input")
        }
    }
}
