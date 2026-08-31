package com.zaid.screenrecorder.core

object FpsPolicy {
    private val ordered = listOf(120, 90, 60, 30)

    fun fallback(requested: Int, supported: Set<Int>): Int? {
        val ceiling = requested.coerceAtMost(120)
        return ordered.firstOrNull { it <= ceiling && it in supported }
    }
}
