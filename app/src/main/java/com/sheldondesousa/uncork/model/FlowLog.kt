package com.sheldondesousa.uncork.model

import android.util.Log

internal fun logFlow(message: String) {
    runCatching { Log.d("UncorkFlow", message) }
}
