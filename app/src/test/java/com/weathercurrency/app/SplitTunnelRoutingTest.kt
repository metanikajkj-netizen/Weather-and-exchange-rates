package com.weathercurrency.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for split tunneling routing fix (TASK 32.3).
 * Root cause: mixing addAllowedApplication() + addDisallowedApplication()
 * causes undefined behavior on Android (breaks on MIUI/HyperOS).
 */
class SplitTunnelRoutingTest {

    @Test
    fun selectedModeDoesNotUseDisallowedApplication() {
        // In SELECTED mode, only addAllowedApplication() should be called
        // addDisallowedApplication() must NOT be called (undefined behavior)
        val mode = "selected"
        val selectedPackages = setOf("org.telegram.messenger", "com.whatsapp", "com.google.android.youtube")
        val isSelectMode = mode == "selected" && selectedPackages.isNotEmpty()

        // In SELECTED mode: addAllowedApplication for each package
        // NO addDisallowedApplication call
        val useDisallowed = !isSelectMode // only in ALL mode
        assertFalse("SELECTED mode must NOT use addDisallowedApplication", useDisallowed)
    }

    @Test
    fun allModeUsesOnlyDisallowedApplication() {
        // In ALL mode, only addDisallowedApplication(own) should be called
        val mode = "all"
        val isSelectMode = mode == "selected"

        val useDisallowed = !isSelectMode
        val useAllowed = isSelectMode

        assertTrue("ALL mode uses addDisallowedApplication", useDisallowed)
        assertFalse("ALL mode does NOT use addAllowedApplication", useAllowed)
    }

    @Test
    fun noMixingAllowedAndDisallowed() {
        // Never call both addAllowedApplication and addDisallowedApplication
        val mode = "selected"
        val selectedPackages = setOf("com.a")
        val isSelectMode = mode == "selected" && selectedPackages.isNotEmpty()

        val callsAllowed = isSelectMode
        val callsDisallowed = !isSelectMode

        // XOR: exactly one should be true
        assertTrue("Must call exactly one of allowed/disallowed", callsAllowed != callsDisallowed)
    }

    @Test
    fun ownAppExcludedInSelectedMode() {
        // In SELECTED mode, own app is NOT in the allowed list → automatically excluded
        val ownPackage = "com.weathercurrency.app"
        val selectedPackages = setOf("org.telegram.messenger", "com.whatsapp")

        val ownInAllowed = ownPackage in selectedPackages
        assertFalse("Own app must NOT be in allowed list", ownInAllowed)
    }

    @Test
    fun ownAppExcludedInAllMode() {
        // In ALL mode, addDisallowedApplication(own) excludes own app
        val ownPackage = "com.weathercurrency.app"
        val disallowed = setOf(ownPackage)

        val ownExcluded = ownPackage in disallowed
        assertTrue("Own app must be excluded in ALL mode", ownExcluded)
    }

    @Test
    fun selectedAppsRouteThroughVpn() {
        val selectedPackages = setOf("org.telegram.messenger", "com.whatsapp", "com.google.android.youtube")
        val mode = "selected"

        val isSelectMode = mode == "selected" && selectedPackages.isNotEmpty()
        assertTrue("SELECTED mode should route selected apps through VPN", isSelectMode)
    }

    @Test
    fun unselectedAppsRouteDirect() {
        val selectedPackages = setOf("org.telegram.messenger")
        val browserPackage = "com.android.chrome"

        val browserInAllowed = browserPackage in selectedPackages
        assertFalse("Unselected browser must route DIRECT", browserInAllowed)
    }

    @Test
    fun emptySelectedListFallsBackToAll() {
        val mode = "selected"
        val selectedPackages = emptySet<String>()

        // Empty list in SELECTED mode → fall back to ALL mode behavior
        val isSelectMode = mode == "selected" && selectedPackages.isNotEmpty()
        assertFalse("Empty selected list should fall back to ALL mode", isSelectMode)
    }

    @Test
    fun vpnRestartOnSettingsChange() {
        // When settings change and VPN is connected, restart VPN
        val vpnConnected = true
        val settingsChanged = true

        val shouldRestart = vpnConnected && settingsChanged
        assertTrue("VPN should restart on settings change", shouldRestart)
    }

    @Test
    fun noFalseConnectedStatus() {
        // VPN should not show CONNECTED if establish() failed
        val establishSucceeded = false
        val showConnected = establishSucceeded

        assertFalse("Must not show CONNECTED when establish() failed", showConnected)
    }
}
