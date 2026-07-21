@file:Suppress("unused")

package com.valhalla.superuser.utils

import android.util.Log
import com.valhalla.superuser.BuildConfig

public object Logger {

    public fun d(tag: String?, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag ?: "", message)
        }
    }

    public fun i(tag: String?, message: String) {
        if (BuildConfig.DEBUG) {
            Log.i(tag ?: "", message)
        }
    }

    public fun w(tag: String?, message: String) {
        if (BuildConfig.DEBUG) {
            Log.w(tag ?: "", message)
        }
    }

    public fun v(tag: String?, message: String) {
        if (BuildConfig.DEBUG) {
            Log.v(tag ?: "", message)
        }
    }

    public fun e(tag: String?, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            Log.e(tag ?: "", message, throwable)
        }
    }
}
