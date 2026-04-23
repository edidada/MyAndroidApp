package com.example.utils

class KotlinHelper {

    private var message: String = "Hello from Kotlin!"

    fun getMessage(): String {
        return message
    }

    fun setMessage(msg: String) {
        message = msg
    }

    @JvmOverloads
    fun greet(name: String = "World"): String {
        return "Hello, $name!"
    }

    fun calculateSum(vararg numbers: Int): Int {
        return numbers.sum()
    }

    companion object {
        @JvmStatic
        fun staticMethod(): String {
            return "This is a static method from Kotlin!"
        }
    }
}
