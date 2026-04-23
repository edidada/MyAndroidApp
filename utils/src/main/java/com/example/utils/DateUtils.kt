package com.example.utils

import java.text.SimpleDateFormat
import java.util.*

object DateUtils {

    private const val DEFAULT_PATTERN = "yyyy-MM-dd HH:mm:ss"

    @JvmStatic
    fun getCurrentTime(): String {
        val sdf = SimpleDateFormat(DEFAULT_PATTERN, Locale.getDefault())
        return sdf.format(Date())
    }

    @JvmStatic
    fun getCurrentTime(pattern: String): String {
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        return sdf.format(Date())
    }

    @JvmStatic
    fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat(DEFAULT_PATTERN, Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    @JvmStatic
    fun getTimestamp(): Long {
        return System.currentTimeMillis()
    }

    @JvmStatic
    fun addDays(days: Int): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, days)
        val sdf = SimpleDateFormat(DEFAULT_PATTERN, Locale.getDefault())
        return sdf.format(calendar.time)
    }
}
