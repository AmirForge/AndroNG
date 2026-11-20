package com.v2ray.ang.service

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AetherProbeRouteTest {
    @Test fun testsTheExitRuleInsteadOfGivingAHealthyWarpOnlyTick() {
        val plain = ProfileItem.create(EConfigType.AETHER)
        assertTrue(usesDirectAetherProbe(plain, false))
        assertFalse(usesDirectAetherProbe(plain, true))
        assertFalse(usesDirectAetherProbe(plain.copy(aetherOutrole = "exit"), false))
        assertFalse(usesDirectAetherProbe(plain.copy(aetherEmbeddedOutrole = ProfileItem.create(EConfigType.VLESS)), false))
        assertFalse(usesDirectAetherProbe(ProfileItem.create(EConfigType.VLESS), false))
    }
}
