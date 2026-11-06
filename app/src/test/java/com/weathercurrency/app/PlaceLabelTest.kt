package com.weathercurrency.app

import com.weathercurrency.app.location.PlaceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceLabelTest {

    @Test
    fun prefersLocalityOverEverything() {
        assertEquals(
            "Железнодорожный",
            PlaceLabel.from(
                locality = "Железнодорожный",
                subAdminArea = "Городской округ",
                adminArea = "Московская область",
                countryName = "Россия"
            )
        )
    }

    @Test
    fun neverAppendsHouseNumber() {
        // Old bug: featureName «113» was appended → «Железнодорожный, 113».
        // PlaceLabel does not accept featureName at all; locality alone wins.
        assertEquals(
            "Железнодорожный",
            PlaceLabel.from(locality = "Железнодорожный", subAdminArea = null, adminArea = null)
        )
    }

    @Test
    fun fallsBackToSubAdminAreaWhenNoLocality() {
        assertEquals(
            "Балашихинский район",
            PlaceLabel.from(
                locality = null,
                subAdminArea = "Балашихинский район",
                adminArea = "Московская область"
            )
        )
    }

    @Test
    fun fallsBackToAdminAreaWhenNoLocalityOrDistrict() {
        assertEquals(
            "Московская область",
            PlaceLabel.from(locality = null, subAdminArea = null, adminArea = "Московская область")
        )
    }

    @Test
    fun fallsBackToCountryLast() {
        assertEquals(
            "Россия",
            PlaceLabel.from(locality = null, subAdminArea = null, adminArea = null, countryName = "Россия")
        )
    }

    @Test
    fun blankAndNullStringsAreSkipped() {
        assertEquals(
            "Москва",
            PlaceLabel.from(locality = "  ", subAdminArea = "", adminArea = "Москва")
        )
        assertEquals(
            "Москва",
            PlaceLabel.from(locality = "null", subAdminArea = null, adminArea = "Москва")
        )
    }

    @Test
    fun pureDigitsRejectedAsPlaceName() {
        // House-number-like values must not become the city label.
        assertEquals(
            "Тюмень",
            PlaceLabel.from(locality = "113", subAdminArea = null, adminArea = "Тюмень")
        )
        // Nothing usable → null (resolveOrFallback then returns FALLBACK)
        assertNull(PlaceLabel.from(locality = "113", subAdminArea = "  ", adminArea = null))
    }

    @Test
    fun resolveOrFallbackWhenNothingAvailable() {
        assertEquals(
            PlaceLabel.FALLBACK,
            PlaceLabel.resolveOrFallback(null, null, null, null)
        )
        assertEquals("Моё местоположение", PlaceLabel.FALLBACK)
    }

    @Test
    fun resolveOrFallbackUsesLocality() {
        assertEquals(
            "Москва",
            PlaceLabel.resolveOrFallback("Москва", null, null)
        )
    }

    @Test
    fun trimsWhitespace() {
        assertEquals(
            "Казань",
            PlaceLabel.from(locality = " Казань ", subAdminArea = null, adminArea = null)
        )
    }
}
