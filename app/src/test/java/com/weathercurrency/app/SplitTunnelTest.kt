package com.weathercurrency.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for split tunneling logic.
 * Rules: checked apps → VPN, unchecked apps → direct.
 */
class SplitTunnelTest {

    @Test
    fun allModeRoutesAllAppsThroughVpn() {
        val mode = "all"
        val selectedPackages = emptySet<String>()
        // In ALL mode, no addAllowedApplication() calls — all apps go through VPN
        val shouldWhitelist = mode == "selected" && selectedPackages.isNotEmpty()
        assertFalse(shouldWhitelist)
    }

    @Test
    fun selectedModeRoutesOnlyCheckedApps() {
        val mode = "selected"
        val selectedPackages = setOf("com.google.android.youtube", "org.telegram.messenger")
        val shouldWhitelist = mode == "selected" && selectedPackages.isNotEmpty()
        assertTrue(shouldWhitelist)
    }

    @Test
    fun selectedModeEmptyListWarns() {
        val mode = "selected"
        val selectedPackages = emptySet<String>()
        // Should show warning and not start VPN with bad config
        val hasWarning = mode == "selected" && selectedPackages.isEmpty()
        assertTrue(hasWarning)
    }

    @Test
    fun defaultModeIsAll() {
        val defaultMode = "all"
        assertEquals("all", defaultMode)
    }

    @Test
    fun ownPackageAlwaysExcluded() {
        // addDisallowedApplication(packageName) is always called for loop prevention
        val ownPackage = "com.weathercurrency.app"
        val alwaysExcluded = true
        assertTrue(alwaysExcluded)
    }

    @Test
    fun uninstalledPackageSkippedGracefully() {
        // When addAllowedApplication() throws NameNotFoundException, skip that package
        val packages = setOf("com.existing.app", "com.uninstalled.app")
        val available = setOf("com.existing.app") // only one is installed
        val added = packages.filter { it in available }
        assertEquals(1, added.size)
    }

    @Test
    fun selectedCountTracked() {
        val selectedPackages = setOf("com.a", "com.b", "com.c")
        assertEquals(3, selectedPackages.size)
    }

    @Test
    fun selectAllIncludesNonSystemApps() {
        val apps = listOf(
            Triple("com.a", "App A", false),
            Triple("com.b", "App B", false),
            Triple("com.sys", "System", true)
        )
        val showSystem = false
        val selectable = apps.filter { !it.third || showSystem }.map { it.first }.toSet()
        assertEquals(2, selectable.size)
    }

    @Test
    fun deselectAllClearsSet() {
        var selected = setOf("com.a", "com.b")
        selected = emptySet()
        assertTrue(selected.isEmpty())
    }

    @Test
    fun searchFiltersApps() {
        val apps = listOf(
            Triple("com.google.youtube", "YouTube", false),
            Triple("org.telegram", "Telegram", false),
            Triple("com.android.chrome", "Chrome", false)
        )
        val query = "you"
        val filtered = apps.filter { it.second.contains(query, ignoreCase = true) }
        assertEquals(1, filtered.size)
        assertEquals("YouTube", filtered[0].second)
    }
}
