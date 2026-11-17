package com.fullstack.ble.sdk.internal

import android.os.Handler
import android.os.Looper
import android.util.Log

internal class CallbackDispatcher {
    val handler = Handler(Looper.getMainLooper())

    fun dispatch(block: () -> Unit) {
        handler.post {
            try {
                block()
            } catch (throwable: Throwable) {
                Log.e("FullstackBleSdk", "Application callback failed", throwable)
            }
        }
    }
}
