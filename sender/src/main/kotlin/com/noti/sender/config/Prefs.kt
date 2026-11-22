package com.noti.sender.config

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * One-line settings: `var gzipEnabled by prefs.boolean(KEY_GZIP, false)` reads and writes that key,
 * exactly as the hand-written getter/setter pairs did (read on every get, apply() on every set).
 * Only for the plain ones - a setting that caches, clamps or invalidates something keeps its own.
 */
fun SharedPreferences.string(key: String, default: String): ReadWriteProperty<Any, String> = pref(
    { getString(key, default) ?: default }, { edit { putString(key, it) } },
)

fun SharedPreferences.boolean(key: String, default: Boolean): ReadWriteProperty<Any, Boolean> = pref(
    { getBoolean(key, default) }, { edit { putBoolean(key, it) } },
)

fun SharedPreferences.int(key: String, default: Int): ReadWriteProperty<Any, Int> = pref(
    { getInt(key, default) }, { edit { putInt(key, it) } },
)

fun SharedPreferences.long(key: String, default: Long): ReadWriteProperty<Any, Long> = pref(
    { getLong(key, default) }, { edit { putLong(key, it) } },
)

private inline fun <T> pref(crossinline read: () -> T, crossinline write: (T) -> Unit) = object : ReadWriteProperty<Any, T> {
    override fun getValue(thisRef: Any, property: KProperty<*>): T = read()
    override fun setValue(thisRef: Any, property: KProperty<*>, value: T) = write(value)
}
