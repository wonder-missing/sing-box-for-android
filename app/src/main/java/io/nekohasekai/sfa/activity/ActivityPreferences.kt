package io.nekohasekai.sfa.activity

import android.content.Context

/** Local-only activity log switch and install-version bookkeeping. */
object ActivityPreferences {
    private const val FILE = "activity"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_VERSION_CODE = "last_version_code"
    private const val KEY_LAST_VERSION_NAME = "last_version_name"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    internal fun lastVersionCode(context: Context): Int =
        prefs(context).getInt(KEY_LAST_VERSION_CODE, 0)

    internal fun lastVersionName(context: Context): String =
        prefs(context).getString(KEY_LAST_VERSION_NAME, "").orEmpty()

    internal fun setLastVersion(context: Context, code: Int, name: String) {
        prefs(context).edit()
            .putInt(KEY_LAST_VERSION_CODE, code)
            .putString(KEY_LAST_VERSION_NAME, name)
            .apply()
    }
}
