package com.weathercurrency.app.location

/**
 * Chooses a human-readable place label from reverse-geocode fields.
 *
 * Priority: locality (settlement) → subAdminArea (district) → adminArea (region).
 * Never uses featureName / thoroughfare / subThoroughfare (house numbers, streets).
 * Pure function — unit-testable without Android.
 */
object PlaceLabel {

    const val FALLBACK = "Моё местоположение"

    fun from(
        locality: String?,
        subAdminArea: String?,
        adminArea: String?,
        countryName: String? = null
    ): String? {
        return clean(locality)
            ?: clean(subAdminArea)
            ?: clean(adminArea)
            ?: clean(countryName)
    }

    /** Resolves label for UI; never returns coordinates, never a house number. */
    fun resolveOrFallback(
        locality: String?,
        subAdminArea: String?,
        adminArea: String?,
        countryName: String? = null
    ): String = from(locality, subAdminArea, adminArea, countryName) ?: FALLBACK

    private fun clean(value: String?): String? {
        val v = value?.trim() ?: return null
        if (v.isEmpty()) return null
        if (v.equals("null", ignoreCase = true)) return null
        // Reject pure house numbers / postal codes that sometimes appear in feature-like fields.
        if (v.all { it.isDigit() }) return null
        return v
    }
}
