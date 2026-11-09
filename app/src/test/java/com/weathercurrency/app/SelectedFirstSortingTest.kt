package com.weathercurrency.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for selected-first sorting in Split Tunneling (TASK 33.2).
 * Selected apps appear at top, unselected below, both alphabetically sorted.
 */
class SelectedFirstSortingTest {

    data class App(val packageName: String, val name: String)

    private val apps = listOf(
        App("com.google.android.youtube", "YouTube"),
        App("org.telegram.messenger", "Telegram"),
        App("com.whatsapp", "WhatsApp"),
        App("com.android.chrome", "Chrome"),
        App("com.google.android.maps", "Google Maps"),
    )

    private fun sortSelectedFirst(
        apps: List<App>,
        selected: Set<String>
    ): List<App> {
        return apps.sortedWith(
            compareByDescending<App> { it.packageName in selected }
                .thenBy { it.name.lowercase() }
        )
    }

    @Test
    fun selectedAppsAppearFirst() {
        val selected = setOf("com.android.chrome", "com.google.android.youtube")
        val sorted = sortSelectedFirst(apps, selected)

        // Chrome and YouTube should be first (selected), then Google Maps, Telegram, WhatsApp
        assertEquals("Chrome", sorted[0].name)
        assertEquals("YouTube", sorted[1].name)
    }

    @Test
    fun unselectedAppsBelowSelected() {
        val selected = setOf("com.android.chrome", "com.google.android.youtube")
        val sorted = sortSelectedFirst(apps, selected)

        // Unselected apps come after selected
        assertEquals("Google Maps", sorted[2].name)
        assertEquals("Telegram", sorted[3].name)
        assertEquals("WhatsApp", sorted[4].name)
    }

    @Test
    fun selectedAppsAlphabeticallySorted() {
        val selected = setOf("com.google.android.youtube", "com.android.chrome", "org.telegram.messenger")
        val sorted = sortSelectedFirst(apps, selected)

        // Selected: Chrome, Telegram, YouTube (alphabetical)
        assertEquals("Chrome", sorted[0].name)
        assertEquals("Telegram", sorted[1].name)
        assertEquals("YouTube", sorted[2].name)
    }

    @Test
    fun unselectedAppsAlphabeticallySorted() {
        val selected = setOf("com.android.chrome")
        val sorted = sortSelectedFirst(apps, selected)

        // Unselected: Google Maps, Telegram, WhatsApp (alphabetical)
        assertEquals("Google Maps", sorted[1].name)
        assertEquals("Telegram", sorted[2].name)
        assertEquals("WhatsApp", sorted[3].name)
    }

    @Test
    fun allAppsSelected() {
        val selected = apps.map { it.packageName }.toSet()
        val sorted = sortSelectedFirst(apps, selected)

        // All selected, all alphabetical
        assertEquals("Chrome", sorted[0].name)
        assertEquals("Google Maps", sorted[1].name)
        assertEquals("Telegram", sorted[2].name)
        assertEquals("WhatsApp", sorted[3].name)
        assertEquals("YouTube", sorted[4].name)
    }

    @Test
    fun noAppsSelected() {
        val selected = emptySet<String>()
        val sorted = sortSelectedFirst(apps, selected)

        // All unselected, alphabetical
        assertEquals("Chrome", sorted[0].name)
        assertEquals("Google Maps", sorted[1].name)
        assertEquals("Telegram", sorted[2].name)
        assertEquals("WhatsApp", sorted[3].name)
        assertEquals("YouTube", sorted[4].name)
    }

    @Test
    fun selectAppMovesToTop() {
        // Initially all unselected
        var selected = emptySet<String>()
        var sorted = sortSelectedFirst(apps, selected)
        assertEquals("Chrome", sorted[0].name)

        // Select YouTube
        selected = setOf("com.google.android.youtube")
        sorted = sortSelectedFirst(apps, selected)
        assertEquals("YouTube", sorted[0].name)
    }

    @Test
    fun deselectAppMovesDown() {
        // Start with all selected
        var selected = apps.map { it.packageName }.toSet()
        var sorted = sortSelectedFirst(apps, selected)

        // Deselect Chrome
        selected = selected - "com.android.chrome"
        sorted = sortSelectedFirst(apps, selected)

        // Chrome should now be in unselected section (but still first alphabetically)
        // Selected: Google Maps, Telegram, WhatsApp, YouTube
        // Unselected: Chrome
        assertEquals("Google Maps", sorted[0].name)
        assertEquals("Chrome", sorted[4].name)
    }

    @Test
    fun noDuplicates() {
        val selected = setOf("com.android.chrome", "com.google.android.youtube")
        val sorted = sortSelectedFirst(apps, selected)

        val packageNames = sorted.map { it.packageName }
        assertEquals(packageNames.size, packageNames.toSet().size)
    }

    @Test
    fun searchPreservesSelectedFirst() {
        // When searching, selected apps still appear first among results
        val selected = setOf("com.google.android.youtube")
        val query = "o" // matches Chrome, Google Maps, YouTube

        val filtered = apps.filter { it.name.contains(query, ignoreCase = true) }
            .sortedWith(
                compareByDescending<App> { it.packageName in selected }
                    .thenBy { it.name.lowercase() }
            )

        // YouTube (selected) first, then Chrome, Google Maps (unselected)
        assertEquals("YouTube", filtered[0].name)
    }

    @Test
    fun stableKeys() {
        // Each app should have a unique key for LazyColumn
        val keys = apps.map { it.packageName }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun emptySelectedList() {
        val selected = emptySet<String>()
        val sorted = sortSelectedFirst(apps, selected)
        assertEquals(apps.size, sorted.size)
    }

    @Test
    fun allSelectedList() {
        val selected = apps.map { it.packageName }.toSet()
        val sorted = sortSelectedFirst(apps, selected)
        assertEquals(apps.size, sorted.size)
    }

    @Test
    fun caseInsensitiveSorting() {
        val testApps = listOf(
            App("com.b", "banana"),
            App("com.a", "Apple"),
            App("com.c", "cherry"),
        )
        val sorted = sortSelectedFirst(testApps, emptySet())
        assertEquals("Apple", sorted[0].name)
        assertEquals("banana", sorted[1].name)
        assertEquals("cherry", sorted[2].name)
    }
}
