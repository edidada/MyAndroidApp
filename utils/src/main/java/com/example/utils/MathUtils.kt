package com.example.utils

object MathUtils {

    @JvmStatic
    fun add(a: Int, b: Int): Int {
        return a + b
    }

    @JvmStatic
    fun subtract(a: Int, b: Int): Int {
        return a - b
    }

    @JvmStatic
    fun multiply(a: Int, b: Int): Int {
        return a * b
    }

    @JvmStatic
    fun divide(a: Int, b: Int): Double {
        if (b == 0) {
            throw IllegalArgumentException("Divider cannot be zero")
        }
        return a.toDouble() / b.toDouble()
    }

    @JvmStatic
    fun max(vararg numbers: Int): Int {
        if (numbers.isEmpty()) {
            throw IllegalArgumentException("No numbers provided")
        }
        return numbers.maxOrNull() ?: numbers[0]
    }

    @JvmStatic
    fun min(vararg numbers: Int): Int {
        if (numbers.isEmpty()) {
            throw IllegalArgumentException("No numbers provided")
        }
        return numbers.minOrNull() ?: numbers[0]
    }
}
