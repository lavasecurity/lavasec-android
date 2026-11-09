package com.lavasec.app.platform

fun interface Clock {
    fun nowEpochMillis(): Long
}
