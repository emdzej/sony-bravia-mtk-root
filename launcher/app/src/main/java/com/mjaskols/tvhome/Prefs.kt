package com.mjaskols.tvhome

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tvhome", Context.MODE_PRIVATE)

    /** Switch to [primaryInputId] once per boot when the launcher first starts. */
    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", true)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    /** Input opened on boot; null = first HDMI-CEC device found (the Apple TV). */
    var primaryInputId: String?
        get() = sp.getString("primary_input", null)
        set(v) = sp.edit().putString("primary_input", v).apply()

    var lastAutoStartBoot: Int
        get() = sp.getInt("last_auto_start_boot", -1)
        set(v) = sp.edit().putInt("last_auto_start_boot", v).apply()
}
