package com.v2ray.ang.ui.soar

import com.v2ray.ang.core.soar.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Base64

class SoarEditsTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })

    @Test fun createsSelectsAndRemovesByStableId() {
        val first = updateSoarStore(SoarStore(), SoarAction.Manual("one", "dns.example.com", key))
        assertEquals(1, first.profiles.size)
        val second = updateSoarStore(first, SoarAction.Manual("two", "dns2.example.com", key))
        val selected = updateSoarStore(second, SoarAction.Select(first.selectedId))
        assertEquals("one", selected.profiles.first { it.id == selected.selectedId }.name)
        val removed = updateSoarStore(selected, SoarAction.Remove(selected.selectedId))
        assertEquals("two", removed.profiles.first().name)
        assertEquals(removed.profiles.first().id, removed.selectedId)
        assertTrue(updateSoarStore(removed, SoarAction.Remove(removed.selectedId)).selectedId.isEmpty())
    }

    @Test fun resolverEditReplacesInsteadOfDuplicating() {
        val first = updateSoarStore(SoarStore(), SoarAction.SaveResolvers("home", "1.1.1.1"))
        val edited = updateSoarStore(first, SoarAction.SaveResolvers("office", "8.8.8.8", first.resolverId))
        assertEquals(1, edited.resolverProfiles.size)
        assertEquals(first.resolverId, edited.resolverId)
        assertEquals(listOf("8.8.8.8:53"), edited.resolverProfiles.single().addresses)
        assertEquals("", updateSoarStore(edited, SoarAction.RemoveResolvers(edited.resolverId)).resolverId)
    }

    @Test fun resetRestoresSelectedProfileOnly() {
        val first = updateSoarStore(SoarStore(), SoarAction.Manual("one", "dns.example.com", key))
        val changed = updateSoarStore(first, SoarAction.Settings(SoarSettings(transport = "only", maxStreams = 16), SoarRuntimeSettings(vpnMtu = 1500)))
        assertEquals(16, changed.profiles.single().settings.maxStreams)
        assertEquals(1500, changed.profiles.single().runtime.vpnMtu)
        assertEquals(SoarSettings(), updateSoarStore(changed, SoarAction.Reset).profiles.single().settings)
        assertEquals(SoarRuntimeSettings(), updateSoarStore(changed, SoarAction.Reset).profiles.single().runtime)
    }

    @Test fun invalidOperationsLeaveOriginalImmutableStoreUnchanged() {
        val original = SoarStore()
        listOf(SoarAction.Manual("", "", ""), SoarAction.SaveResolvers("", "1.1.1.1"),
            SoarAction.SaveResolvers("home", "invalid"), SoarAction.Select("missing"),
            SoarAction.SelectResolvers("missing"), SoarAction.Settings(SoarSettings()), SoarAction.Import("invalid")).forEach {
            assertTrue(runCatching { updateSoarStore(original, it) }.isFailure)
        }
        assertEquals(SoarStore(), original)
    }
}
