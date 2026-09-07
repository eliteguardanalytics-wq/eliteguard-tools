package com.eliteguard.checkpoint.util

import org.json.JSONArray
import org.json.JSONObject

/** org.json's optString() turns JSON null into the string "null"; these helpers do not. */
fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)

fun JSONObject.reqStr(key: String): String = str(key) ?: throw IllegalStateException("Missing field '$key'")

fun JSONObject.int(key: String, default: Int = 0): Int = if (!has(key) || isNull(key)) default else optInt(key, default)

fun JSONObject.bool(key: String, default: Boolean = false): Boolean = if (!has(key) || isNull(key)) default else optBoolean(key, default)

inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
    val out = ArrayList<T>(length())
    for (i in 0 until length()) out.add(transform(getJSONObject(i)))
    return out
}
