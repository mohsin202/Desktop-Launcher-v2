package com.android.desktoplauncher

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/**
 * Invisible 1x1 window opened in freeform mode (the same trick the Taskbar app uses, "freeform hack"): once a freeform
 * task exists, apps opened afterwards also open in freeform windows on ROMs that need it.
 */
class FreeformHackActivity : Activity() {
    companion object {
        @Volatile var live: java.lang.ref.WeakReference<FreeformHackActivity>? = null
        fun running() = live?.get()?.let { !it.isFinishing } == true
        fun stop() { live?.get()?.finish(); live = null }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        live = java.lang.ref.WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.setLayout(1, 1)
    }

    override fun onDestroy() {
        if (live?.get() === this) live = null
        super.onDestroy()
    }
}
