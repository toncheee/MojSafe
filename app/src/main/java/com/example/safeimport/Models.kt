package com.example.safeimport

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single imported entry (folder or card) recovered from the old Handy Safe database.
 *
 * `strings` holds the raw text fields recovered from the encrypted record, in the order
 * they were stored. For "card" entries (attr == 5) these are almost always label/value
 * pairs: strings[0]="Title", strings[1]="My Visa", strings[2]="Bank", strings[3]="Example
 * Bank", and so on. We keep them as a flat list rather than guessing field names, since
 * the exact field-type table was not fully reverse engineered — this way no data is lost
 * even if a label is ambiguous.
 */
data class VaultItem(
    val uid: Long,
    val parent: Long,
    val attr: Int,
    val time: Long,
    val strings: List<String>
) {
    /** True for folder-like entries (top-level categories). */
    val isFolder: Boolean get() = attr == 1 || attr == 3

    /** Best-effort display title: first string, or "(untitled)". */
    val title: String get() = strings.firstOrNull()?.takeIf { it.isNotBlank() } ?: "(untitled)"

    /** Pairs up the flat string list as (label, value) rows for display, when possible. */
    fun fieldPairs(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < strings.size) {
            val label = strings[i]
            val value = strings.getOrNull(i + 1) ?: ""
            out.add(label to value)
            i += 2
        }
        return out
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("parent", parent)
        put("attr", attr)
        put("time", time)
        put("strings", JSONArray(strings))
    }

    /** Returns a copy of this item with its flat string list rebuilt from label/value pairs. */
    fun withFieldPairs(pairs: List<Pair<String, String>>): VaultItem {
        val flat = mutableListOf<String>()
        pairs.forEach { (l, v) -> flat.add(l); flat.add(v) }
        return copy(strings = flat)
    }

    companion object {
        fun fromJson(o: JSONObject): VaultItem {
            val arr = o.optJSONArray("strings") ?: JSONArray()
            val strings = (0 until arr.length()).map { arr.getString(it) }
            return VaultItem(
                uid = o.optLong("uid"),
                parent = o.optLong("parent"),
                attr = o.optInt("attr"),
                time = o.optLong("time"),
                strings = strings
            )
        }
    }
}

fun List<VaultItem>.toJsonArray(): JSONArray {
    val arr = JSONArray()
    forEach { arr.put(it.toJson()) }
    return arr
}

fun parseVaultItems(jsonText: String): List<VaultItem> {
    val arr = JSONArray(jsonText)
    return (0 until arr.length()).map { VaultItem.fromJson(arr.getJSONObject(it)) }
}

/** attr value used for a plain folder (matches what Handy Safe used). */
const val ATTR_FOLDER = 1

/** attr value used for a card/entry with fields. */
const val ATTR_CARD = 5

/** A uid that won't collide with recovered Handy Safe uids (those were much smaller ints). */
fun newUid(): Long = System.currentTimeMillis()
