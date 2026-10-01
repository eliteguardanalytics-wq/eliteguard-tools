package com.eliteguard.checkpoint.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Tiny background-work helper: run [work] on a pool thread, deliver the result on the main thread. */
object Bg {
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, onDone: (Result<T>) -> Unit) {
        pool.execute {
            val result = runCatching(work)
            main.post { onDone(result) }
        }
    }

    fun post(action: () -> Unit) {
        main.post(action)
    }

    fun postDelayed(delayMs: Long, action: () -> Unit) {
        main.postDelayed(action, delayMs)
    }
}
