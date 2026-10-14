package com.offlinewiki.simple.data

import android.content.Context

/** The user's light or dark choice from the in-app switch. Null means "follow the system setting". */
class ThemeSetting(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var dark: Boolean?
        get() = if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else null
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY) else putBoolean(KEY, value) }.apply()
        }

    private companion object {
        const val KEY = "dark"
    }
}
