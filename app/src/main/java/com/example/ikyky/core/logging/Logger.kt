package com.example.ikyky.core.logging

import android.util.Log

/**
 * Thin logging seam. Keeps `android.util.Log` out of domain/data code paths and
 * lets tests assert on log output if needed later.
 */
interface Logger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

class AndroidLogger : Logger {
    override fun d(tag: String, message: String) { Log.d(tag, message) }
    override fun i(tag: String, message: String) { Log.i(tag, message) }
    override fun w(tag: String, message: String, throwable: Throwable?) { Log.w(tag, message, throwable) }
    override fun e(tag: String, message: String, throwable: Throwable?) { Log.e(tag, message, throwable) }
}
