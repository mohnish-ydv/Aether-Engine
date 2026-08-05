package com.mohnishraj.aether.core.time

fun interface EngineClock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = EngineClock { System.currentTimeMillis() }
    }
}
