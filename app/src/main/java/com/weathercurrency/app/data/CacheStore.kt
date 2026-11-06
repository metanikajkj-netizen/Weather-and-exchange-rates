package com.weathercurrency.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.weathercurrency.app.model.CryptoSnapshot
import com.weathercurrency.app.model.CurrencySnapshot
import com.weathercurrency.app.model.WeatherSnapshot
import com.weathercurrency.app.update.UpdateAutoLogic
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "weather_currency_cache")

/**
 * Persists last successful weather + currency + crypto payloads via DataStore.
 * Coordinates are never logged; only display data is stored.
 */
class CacheStore(
    private val context: Context,
    private val gson: Gson = Gson()
) {
    private object Keys {
        val WEATHER = stringPreferencesKey("weather_json")
        val CURRENCY = stringPreferencesKey("currency_json")
        val CRYPTO = stringPreferencesKey("crypto_json")
        /** Selected crypto display currency: "RUB" | "USD". */
        val CRYPTO_CCY = stringPreferencesKey("crypto_display_currency")
        val UPDATE_AUTO = booleanPreferencesKey("update_auto_check")
        val UPDATE_NOTIFY = booleanPreferencesKey("update_notify")
        val UPDATE_LAST_CHECK = longPreferencesKey("update_last_check")
        val UPDATE_LAST_NOTIFIED_VC = longPreferencesKey("update_last_notified_vc")
        val UPDATE_DIALOG_POSTPONE = longPreferencesKey("update_dialog_postpone_until")
        val UPDATE_LAST_DIALOG_VC = longPreferencesKey("update_last_dialog_vc")
        // Legacy key from diagnostic mode (v1.9) — intentionally unused, ignored on load.
        val UPDATE_TEST_MODE_LEGACY = booleanPreferencesKey("update_test_mode_60s")
    }

    suspend fun saveWeather(snapshot: WeatherSnapshot) {
        context.dataStore.edit { it[Keys.WEATHER] = gson.toJson(snapshot) }
    }

    suspend fun loadWeather(): WeatherSnapshot? {
        val raw = context.dataStore.data.first()[Keys.WEATHER] ?: return null
        return try {
            gson.fromJson(raw, WeatherSnapshot::class.java)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun saveCurrency(snapshot: CurrencySnapshot) {
        context.dataStore.edit { it[Keys.CURRENCY] = gson.toJson(snapshot) }
    }

    suspend fun loadCurrency(): CurrencySnapshot? {
        val raw = context.dataStore.data.first()[Keys.CURRENCY] ?: return null
        return try {
            gson.fromJson(raw, CurrencySnapshot::class.java)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun saveCrypto(snapshot: CryptoSnapshot) {
        context.dataStore.edit { it[Keys.CRYPTO] = gson.toJson(snapshot) }
    }

    suspend fun loadCrypto(): CryptoSnapshot? {
        val raw = context.dataStore.data.first()[Keys.CRYPTO] ?: return null
        return try {
            gson.fromJson(raw, CryptoSnapshot::class.java)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun saveCryptoCurrency(code: String) {
        context.dataStore.edit { it[Keys.CRYPTO_CCY] = code }
    }

    suspend fun loadCryptoCurrency(): String? =
        context.dataStore.data.first()[Keys.CRYPTO_CCY]

    suspend fun loadUpdateSettings(): UpdateAutoLogic.UpdateSettings {
        val p = context.dataStore.data.first()
        return UpdateAutoLogic.UpdateSettings(
            autoCheckEnabled = p[Keys.UPDATE_AUTO] ?: true,
            notifyEnabled = p[Keys.UPDATE_NOTIFY] ?: true,
            lastCheckMillis = p[Keys.UPDATE_LAST_CHECK] ?: 0L,
            lastNotifiedVersionCode = p[Keys.UPDATE_LAST_NOTIFIED_VC] ?: 0L,
            dialogPostponeUntilMillis = p[Keys.UPDATE_DIALOG_POSTPONE] ?: 0L,
            lastDialogVersionCode = p[Keys.UPDATE_LAST_DIALOG_VC] ?: 0L
        )
    }

    suspend fun saveUpdateSettings(s: UpdateAutoLogic.UpdateSettings) {
        context.dataStore.edit { p ->
            p[Keys.UPDATE_AUTO] = s.autoCheckEnabled
            p[Keys.UPDATE_NOTIFY] = s.notifyEnabled
            p[Keys.UPDATE_LAST_CHECK] = s.lastCheckMillis
            p[Keys.UPDATE_LAST_NOTIFIED_VC] = s.lastNotifiedVersionCode
            p[Keys.UPDATE_DIALOG_POSTPONE] = s.dialogPostponeUntilMillis
            p[Keys.UPDATE_LAST_DIALOG_VC] = s.lastDialogVersionCode
            // Drop legacy diagnostic flag so it cannot reappear.
            p.remove(Keys.UPDATE_TEST_MODE_LEGACY)
        }
    }
}
