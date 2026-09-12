package com.example.safeimport

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the vault contents encrypted-at-rest on the device, using a key held in the
 * Android Keystore (via Jetpack Security). This is where data lives *after* it has been
 * imported once from the plaintext JSON export produced during migration — the plaintext
 * file should be deleted once import succeeds.
 */
class VaultRepository(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "safe_import_vault",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun hasData(): Boolean = prefs.contains(KEY_ITEMS)

    fun loadItems(): List<VaultItem> {
        val json = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return parseVaultItems(json)
    }

    fun saveItems(items: List<VaultItem>) {
        prefs.edit()
            .putString(KEY_ITEMS, items.toJsonArray().toString())
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    // --- App lock (separate from the migrated Handy Safe data) ---

    fun hasLockPassword(): Boolean = prefs.contains(KEY_LOCK_PASSWORD)

    fun setLockPassword(newPassword: String) {
        prefs.edit().putString(KEY_LOCK_PASSWORD, newPassword).apply()
    }

    fun verifyLockPassword(candidate: String): Boolean =
        prefs.getString(KEY_LOCK_PASSWORD, null) == candidate

    // --- Theme preference ---

    /** "light", "dark", or "system". */
    fun getThemeMode(): String = prefs.getString(KEY_THEME_MODE, "system") ?: "system"

    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
    }

    companion object {
        private const val KEY_ITEMS = "items_json"
        private const val KEY_LOCK_PASSWORD = "app_lock_password"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
