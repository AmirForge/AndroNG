package com.v2ray.ang.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AetherFmt
import com.v2ray.ang.util.JsonUtil

class SmartRecoveryBudgetTest {
    @Test fun `smart recovery leaves the initial connection window unchanged`() {
        assertFalse(SmartRecoveryBudget.boundsWarmUp(false, false))
        assertFalse(SmartRecoveryBudget.boundsWarmUp(true, false))
        assertFalse(SmartRecoveryBudget.boundsWarmUp(false, true))
        assertTrue(SmartRecoveryBudget.boundsWarmUp(true, true))
    }
    @Test fun `an exit waits briefly for an existing reload but never waits forever`() {
        assertFalse(SmartRecoveryBudget.waitsForReload(false, true, 0))
        assertFalse(SmartRecoveryBudget.waitsForReload(true, false, 0))
        repeat(50) { assertTrue(SmartRecoveryBudget.waitsForReload(true, true, it)) }
        assertFalse(SmartRecoveryBudget.waitsForReload(true, true, 50))
        assertEquals(5000L, SmartRecoveryBudget.RELOAD_WAIT_MS * SmartRecoveryBudget.MAX_RELOAD_WAITS)
    }
    @Test fun `one failure is tolerated and successful traffic resets failures`() {
        val budget = SmartRecoveryBudget()
        assertFalse(budget.observe(false)); assertFalse(budget.observe(true))
        assertFalse(budget.observe(false)); assertTrue(budget.observe(false))
    }
    @Test fun `at most three retries until success or a new upstream resets the budget`() {
        val budget = SmartRecoveryBudget()
        repeat(3) { assertTrue(budget.claimAttempt()) }
        repeat(5) { assertFalse(budget.claimAttempt()) }
        assertFalse(budget.observe(true)); assertTrue(budget.claimAttempt())
        budget.reset(); repeat(3) { assertTrue(budget.claimAttempt()) }; assertFalse(budget.claimAttempt())
    }
    @Test fun `smart selection survives links and storage and is opt in for multiple saved endpoints`() {
        val profile = ProfileItem.create(EConfigType.AETHER).apply {
            aetherProtocol = "masque"; aetherTransport = "h2"; server = "192.0.2.1"; serverPort = "443"
            aetherEndpointCount = 2; aetherEndpoints = listOf("h2@192.0.2.1:443", "h2@192.0.2.2:443")
        }
        val ordinary = AetherCore.of(profile, 1085)
        assertFalse(ordinary.smartSelection)
        profile.aetherSmartSelection = true
        val smart = AetherCore.of(profile, 1085)
        assertEquals(ordinary.arguments, smart.arguments)
        assertEquals(ordinary.alternatives, smart.alternatives)
        val imported = AetherFmt.parseProfile("aether://" + AetherFmt.toUri(profile))
        assertEquals(true, imported.aetherSmartSelection)
        val restored = JsonUtil.fromJsonSafe(JsonUtil.toJson(imported), ProfileItem::class.java)!!
        assertTrue(AetherCore.of(restored, 1085).through(1086).smartSelection)
        assertFalse(AetherCore.of(restored.copy(aetherEndpoints = emptyList()), 1085).smartSelection)
        assertFalse(AetherCore.of(restored.copy(aetherCommand = "aether --protocol masque --peer 192.0.2.1:443"), 1085).smartSelection)
        val legacy = AetherFmt.parseProfile("aether://192.0.2.1:443?protocol=masque")
        assertNull(legacy.aetherSmartSelection)
        assertFalse(AetherCore.of(legacy, 1085).smartSelection)
    }
}
