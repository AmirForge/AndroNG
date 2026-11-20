package com.v2ray.ang.ui.main

import com.v2ray.ang.R
import com.v2ray.ang.core.AetherIdentity
import com.v2ray.ang.core.AetherKey
import com.v2ray.ang.core.AetherKeys
import com.v2ray.ang.core.AetherScanResult
import com.v2ray.ang.core.CoreConfigContextBuilder
import com.v2ray.ang.dto.AetherEndpoint
import com.v2ray.ang.dto.entities.ProfileEntry
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherKeyKind
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AetherFmt
import com.v2ray.ang.fmt.AetherSetupLink
import com.v2ray.ang.ui.server.AetherEditorSource
import com.v2ray.ang.ui.server.AetherKeysSource
import com.v2ray.ang.ui.server.AetherSession
import com.v2ray.ang.ui.server.ProfileEditorSource
import com.v2ray.ang.util.JsonUtil
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.util.Base64

class QuickAetherProfileTest {
    private val keys = mock<AetherKeysSource>()
    private val editor = mock<AetherEditorSource>()
    private val profiles = mock<ProfileEditorSource>()
    private val creator = QuickAetherProfile(keys, editor, profiles)
    private val endpoint = AetherEndpoint("2001:db8::1", 8443)

    @Test
    fun `a single scanned UDP endpoint stores HTTP3 as the connection transport`() = runTest {
        readyToScan()
        whenever(editor.scan(any(), any())).thenReturn(AetherScanResult(endpoint, transport = com.v2ray.ang.enums.AetherTransport.HTTP3))
        whenever(profiles.storeProfile(eq(""), any())).thenReturn("guid")
        assertNull(creator.create())
        val saved = argumentCaptor<ProfileItem>()
        verify(profiles).storeProfile(eq(""), saved.capture())
        assertEquals("h3", saved.firstValue.aetherTransport)
        assertEquals(endpoint.host, saved.firstValue.server)
    }

    @Test
    fun `renews all keys then scans and stores a dual stack HTTP2 MASQUE profile`() = runTest {
        readyToScan()
        whenever(editor.scan(any(), any())).thenReturn(AetherScanResult(endpoint))
        whenever(profiles.storeProfile(eq(""), any())).thenReturn("guid")

        assertNull(creator.create())

        val scanned = argumentCaptor<ProfileItem>()
        val ordered = inOrder(keys, editor, profiles)
        ordered.verify(keys).renew(eq(AetherKeyKind.ALL), argThat { AetherKeys.kindOf(this) == AetherKeyKind.ALL }, any(), any())
        ordered.verify(editor).scan(any(), any())
        ordered.verify(profiles).storeProfile(eq(""), scanned.capture())
        with(scanned.firstValue) {
            assertEquals(EConfigType.AETHER, configType)
            assertEquals("masque", aetherProtocol)
            assertEquals("h2", aetherTransport)
            assertEquals("both", aetherIpVersion)
            assertEquals("balanced", aetherScanMode)
            assertEquals(endpoint.host, server)
            assertEquals(endpoint.port.toString(), serverPort)
            assertEquals("", subscriptionId)
        }
    }

    @Test
    fun `unavailable core and active sessions prevent renewal or scanning`() = runTest {
        whenever(keys.isCoreAvailable()).thenReturn(false)
        assertEquals(R.string.aether_unsupported_abi, creator.create())
        whenever(keys.isCoreAvailable()).thenReturn(true)
        whenever(keys.activeSession()).thenReturn(AetherSession(AetherProtocol.MASQUE))
        assertEquals(R.string.aether_renew_blocked, creator.create())
        whenever(keys.activeSession()).thenReturn(null)
        readyToScan()
        whenever(editor.activeSession()).thenReturn(AetherSession(AetherProtocol.MASQUE))
        assertEquals(R.string.aether_scan_blocked, creator.create())
        verifyNoInteractions(profiles)
    }

    @Test
    fun `failed renewal or empty scan never stores a profile`() = runTest {
        whenever(keys.isCoreAvailable()).thenReturn(true)
        assertEquals(R.string.aether_log_key_renew_failed, creator.create())
        verifyNoInteractions(editor)
        readyToScan()
        assertEquals(R.string.aether_scan_failed, creator.create())
        verifyNoInteractions(profiles)
    }

    @Test
    fun `reports rejected profile writes`() = runTest {
        readyToScan()
        whenever(editor.scan(any(), any())).thenReturn(AetherScanResult(endpoint))
        assertEquals(R.string.toast_failure, creator.create())
    }

    @Test
    fun `copied link retains Outrole and imports only after renewal and a fresh scan`() = runTest {
        val aether = ProfileItem.create(EConfigType.AETHER).apply {
            remarks = "My WARP + test"
            server = "162.159.199.186"
            serverPort = "443"
            aetherProtocol = "masque"
            aetherTransport = "h2"
            aetherIpVersion = "both"
        }
        val outrole = ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "My Outrole #1"
            server = "example.org"
            serverPort = "8443"
            password = "00000000-0000-4000-8000-000000000001"
            subscriptionId = "sender-subscription"
        }
        val link = AetherSetupLink.encode(aether, outrole)
        assertTrue(link.startsWith("andro://"))
        assertFalse(link.contains(outrole.server.orEmpty()))
        assertNotEquals(link, AetherSetupLink.encode(aether, outrole))
        val offset = link.length / 2
        val tampered = link.replaceRange(offset, offset + 1, if (link[offset] == 'A') "B" else "A")
        assertNull(AetherSetupLink.parse(tampered))
        val ordinary = EConfigType.AETHER.protocolScheme + AetherFmt.toUri(aether)
        assertEquals(emptyList<String>() to ordinary, AetherSetupLink.splitImport(ordinary))
        val snapshot = outrole.copy(subscriptionId = "", addedTime = 0L, description = null)
        val oldPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(JsonUtil.toJson(snapshot).toByteArray())
        val legacy = "${ordinary.substringBefore('#')}&setup=1&outrole=$oldPayload#${ordinary.substringAfter('#')}"
        assertEquals(snapshot, AetherSetupLink.parse(legacy)?.outrole)
        assertEquals(snapshot, AetherSetupLink.parse(legacy.replace("&outrole=", "&outRule="))?.outrole)
        assertEquals(listOf(link) to ordinary, AetherSetupLink.splitImport("$ordinary\n$link"))
        assertNull(AetherFmt.parse(link))
        assertEquals("443", AetherFmt.parse(ordinary)?.serverPort)

        readyToScan()
        whenever(editor.scan(any(), any())).thenReturn(AetherScanResult(endpoint))
        val progress = mutableListOf<Int>()
        val stored = mutableListOf<ProfileItem>()
        val importer = QuickAetherProfile(keys, editor, profiles, { profile -> stored += profile; true }, { true })
        whenever(editor.missingKeys(any())).thenReturn(listOf("missing-key"))

        assertEquals(QuickAetherProfile.ImportResult(1), importer.importLinks(listOf(link), progress::add))
        inOrder(keys, editor).apply {
            verify(keys).renew(eq(AetherKeyKind.MASQUE), argThat { AetherKeys.kindOf(this) == AetherKeyKind.MASQUE }, any(), any())
            verify(editor).scan(any(), any())
        }
        assertEquals(
            listOf(
                R.string.aether_import_progress_working,
                R.string.aether_import_progress_working,
                R.string.aether_import_progress_finishing,
            ),
            progress,
        )
        val prepared = stored.single()
        val importedOutrole = prepared.aetherEmbeddedOutrole
        assertNull(prepared.aetherOutrole)
        val (resolved, problem) = CoreConfigContextBuilder.resolveAetherOutrole(ProfileEntry("aether", prepared)) { _, _ ->
            error("Embedded Outroles must not read the server list")
        }
        assertNull(problem)
        assertEquals(importedOutrole, resolved?.profile)
        assertEquals(importedOutrole, AetherSetupLink.parse(AetherSetupLink.encode(prepared, importedOutrole))?.outrole)
        assertEquals(aether.remarks, prepared.remarks)
        assertEquals(endpoint.host, prepared.server)
        assertEquals(endpoint.port.toString(), prepared.serverPort)
        assertEquals("h2", prepared.aetherTransport)
        assertEquals(outrole.remarks, importedOutrole?.remarks)
        assertEquals(outrole.server, importedOutrole?.server)
        assertEquals(outrole.password, importedOutrole?.password)
        assertEquals("", importedOutrole?.subscriptionId)
    }

    @Test
    fun `batch import renews each requested key kind once and lets other protocols proceed after a failure`() = runTest {
        readyToScan()
        whenever(editor.missingKeys(any())).thenReturn(listOf("missing-key"))
        whenever(keys.renew(eq(AetherKeyKind.WIREGUARD), any(), any(), any())).thenReturn(null)
        whenever(editor.scan(any(), any())).thenReturn(AetherScanResult(endpoint))
        val stored = mutableListOf<ProfileItem>()
        val importer = QuickAetherProfile(keys, editor, profiles, storeImported = { stored.add(it) })
        val links = listOf("One" to "masque", "Two" to "wg", "Three" to "masque").map { (name, protocol) ->
            AetherSetupLink.encode(ProfileItem.create(EConfigType.AETHER).apply {
                remarks = name
                aetherProtocol = protocol
            }, null)
        }

        assertEquals(QuickAetherProfile.ImportResult(2, R.string.aether_log_key_renew_failed), importer.importLinks(links) {})
        assertEquals(listOf("One", "Three"), stored.map { it.remarks })
        verify(keys, times(2)).renew(any(), any(), any(), any())
        verify(keys).renew(eq(AetherKeyKind.MASQUE), any(), any(), any())
        verify(keys).renew(eq(AetherKeyKind.WIREGUARD), any(), any(), any())
    }

    @Test
    fun `invalid Outrole payload never renews keys and failed scans never publish either profile`() = runTest {
        val stored = mutableListOf<ProfileItem>()
        val importer = QuickAetherProfile(keys, editor, profiles, { profile -> stored += profile; true }, { true })
        val profile = ProfileItem.create(EConfigType.AETHER).apply { remarks = "WARP" }
        val link = AetherSetupLink.encode(profile, null)
        val invalid = link.replace("&setup=1", "&setup=1&outrole=%25")
        assertEquals(QuickAetherProfile.ImportResult(0, R.string.toast_failure), importer.importLinks(listOf(invalid)) {})
        verifyNoInteractions(keys, editor)
        readyToScan()
        assertEquals(QuickAetherProfile.ImportResult(0, R.string.aether_scan_failed), importer.importLinks(listOf(link)) {})
        assertEquals(emptyList<ProfileItem>(), stored)
    }

    @Test
    fun `profiles with removed connection modes are not imported`() = runTest {
        val profile = ProfileItem.create(EConfigType.AETHER).apply {
            remarks = "Tor only"
            aetherTor = "only"
        }
        val outrole = ProfileItem.create(EConfigType.SOCKS).apply {
            remarks = "Outrole"
            server = "example.org"
            serverPort = "1080"
        }
        val legacy = AetherSetupLink.encode(profile, null)
        assertEquals("only", AetherFmt.parse(legacy)?.aetherTor)

        val stored = mutableListOf<ProfileItem>()
        val importer = QuickAetherProfile(keys, editor, profiles, { profile -> stored += profile; true }, { true })
        val progress = mutableListOf<Int>()
        val link = AetherSetupLink.encode(profile, outrole)
        val psiphon = profile.copy(aetherTor = null, aetherPsiphon = "only")
        val psiphonLink = AetherSetupLink.encode(psiphon, outrole)
        assertEquals(QuickAetherProfile.ImportResult(0, R.string.toast_failure), importer.importLinks(listOf(link, psiphonLink), progress::add))
        assertEquals(emptyList<ProfileItem>(), stored)
        assertEquals(emptyList<Int>(), progress)
        verifyNoInteractions(keys, editor)
    }

    @Test
    fun `import waits for the VPN to be disconnected before requesting keys`() = runTest {
        val profile = ProfileItem.create(EConfigType.AETHER).apply { remarks = "WARP" }
        val importer = QuickAetherProfile(keys, editor, profiles, prepareScanNetwork = { false })

        val result = importer.importLinks(listOf(AetherSetupLink.encode(profile, null))) {}

        assertEquals(QuickAetherProfile.ImportResult(0, R.string.aether_scan_disconnect_vpn), result)
        verifyNoInteractions(keys, editor, profiles)
    }

    @Test
    fun `refresh updates the same profile and preserves its settings and Outrole`() = runTest {
        val original = ProfileItem.create(EConfigType.AETHER).apply {
            remarks = "Pinned name"
            subscriptionId = "my-group"
            server = "162.159.199.186"
            serverPort = "443"
            aetherProtocol = "masque"
            aetherTransport = "h2"
            aetherEmbeddedOutrole = ProfileItem.create(EConfigType.SOCKS).copy(remarks = "My Outrole", server = "example.org")
        }
        whenever(profiles.loadProfile("guid")).thenReturn(original)
        readyToScan()
        whenever(editor.scan(any(), any())).thenReturn(AetherScanResult(endpoint))
        var saved: ProfileItem? = null
        val refresher = QuickAetherProfile(keys, editor, profiles, storeRefreshed = { guid, snapshot, updated ->
            assertEquals("guid", guid)
            assertEquals(original, snapshot)
            saved = updated
            true
        })
        val progress = mutableListOf<Int>()

        assertNull(refresher.refresh("guid", progress::add))
        assertEquals(listOf(R.string.aether_import_progress_working, R.string.aether_progress_saving), progress)
        verify(keys, org.mockito.kotlin.never()).renew(any(), any(), any(), any())
        verify(editor).scan(any(), any())
        assertEquals("162.159.199.186", original.server)
        assertEquals(endpoint.host, saved?.server)
        assertEquals(endpoint.port.toString(), saved?.serverPort)
        assertEquals("balanced", saved?.aetherScanMode)
        assertEquals(original, saved?.copy(server = original.server, serverPort = original.serverPort, description = original.description, aetherScanMode = original.aetherScanMode))
    }

    @Test
    fun `refresh skips missing or unsupported profiles and never saves a failed scan`() = runTest {
        val refresher = QuickAetherProfile(keys, editor, profiles, storeRefreshed = { _, _, _ ->
            error("An unsuccessful refresh must not write a profile")
        })

        assertEquals(R.string.toast_failure, refresher.refresh("missing") {})
        val profile = ProfileItem.create(EConfigType.AETHER)
        whenever(profiles.loadProfile("command")).thenReturn(profile.copy(aetherCommand = "--peer 192.0.2.2:443"))
        whenever(profiles.loadProfile("tor")).thenReturn(profile.copy(aetherTor = "only"))
        assertEquals(R.string.toast_failure, refresher.refresh("command") {})
        assertEquals(R.string.toast_failure, refresher.refresh("tor") {})
        verifyNoInteractions(keys, editor)

        whenever(profiles.loadProfile("guid")).thenReturn(profile)
        readyToScan()
        assertEquals(R.string.aether_scan_failed, refresher.refresh("guid") {})
    }

    @Test
    fun `refresh reports a rejected write after a successful scan`() = runTest {
        whenever(profiles.loadProfile("guid")).thenReturn(ProfileItem.create(EConfigType.AETHER))
        readyToScan()
        whenever(editor.scan(any(), any())).thenReturn(AetherScanResult(endpoint))
        val refresher = QuickAetherProfile(keys, editor, profiles, storeRefreshed = { _, _, _ -> false })

        assertEquals(R.string.toast_failure, refresher.refresh("guid") {})
    }

    @Test
    fun `long refresh renews only MASQUE and saves exactly five Ironclad endpoints`() = runTest {
        val original = ProfileItem.create(EConfigType.AETHER).copy(aetherProtocol = "masque", subscriptionId = "group")
        whenever(profiles.loadProfile("guid")).thenReturn(original)
        readyToScan()
        val candidates = (1..5).map { "192.0.2.$it:443" }
        whenever(editor.scan(any(), any())).thenReturn(AetherScanResult(endpoint, candidates = candidates))
        var saved: ProfileItem? = null
        val refresher = QuickAetherProfile(keys, editor, profiles, storeRefreshed = { _, snapshot, updated ->
            assertEquals(original, snapshot)
            saved = updated
            true
        })
        assertNull(refresher.refresh("guid", true) {})
        inOrder(keys, editor).apply {
            verify(keys).renew(eq(AetherKeyKind.MASQUE), any(), any(), any())
            verify(editor).scan(argThat { aetherEndpointCount == 5 && aetherScanMode == "ironclad" }, any())
        }
        assertEquals(candidates, saved?.aetherEndpoints)
        assertEquals("group", saved?.subscriptionId)
        assertNull(original.aetherEndpointCount)
    }

    @Test
    fun `deep refresh accepts one healthy endpoint when a full batch is unavailable`() = runTest {
        whenever(profiles.loadProfile("guid")).thenReturn(ProfileItem.create(EConfigType.AETHER))
        readyToScan()
        whenever(editor.scan(any(), any())).thenReturn(AetherScanResult(endpoint, candidates = listOf("192.0.2.1:443")))
        var saved: ProfileItem? = null
        val refresher = QuickAetherProfile(keys, editor, profiles, storeRefreshed = { _, _, updated -> saved = updated; true })
        assertNull(refresher.refresh("guid", true) {})
        assertEquals(listOf("192.0.2.1:443"), saved?.aetherEndpoints)
    }

    @Test
    fun `normal refresh with missing identity never enrolls or scans`() = runTest {
        whenever(profiles.loadProfile("guid")).thenReturn(ProfileItem.create(EConfigType.AETHER))
        whenever(editor.missingKeys(any())).thenReturn(listOf("masque.json"))
        assertEquals(R.string.aether_scan_failed, creator.refresh("guid") {})
        verifyNoInteractions(keys)
        verify(editor, org.mockito.kotlin.never()).scan(any(), any())
    }

    @Test
    fun `managed setup import keeps its group and embedded rules`() = runTest {
        readyToScan()
        whenever(editor.scan(any(), any())).thenReturn(AetherScanResult(endpoint))
        val rule = ProfileItem.create(EConfigType.SOCKS).copy(remarks = "My rule", server = "example.org", serverPort = "1080")
        val stored = mutableListOf<ProfileItem>()
        val importer = QuickAetherProfile(keys, editor, profiles, storeImported = { stored.add(it) }, validateOutrole = { true })
        val link = AetherSetupLink.encode(ProfileItem.create(EConfigType.AETHER), rule)
        assertEquals(1, importer.importLinks(listOf(link), "_andromeda_free") {}.count)
        assertEquals("_andromeda_free", stored.single().subscriptionId)
        assertEquals(rule.server, stored.single().aetherEmbeddedOutrole?.server)
    }

    private fun setupLink(name: String, protocol: String = "masque", count: Int = 1, sni: String? = null, network: String? = null): String =
        AetherSetupLink.encode(ProfileItem.create(EConfigType.AETHER).copy(
            remarks = name, aetherProtocol = protocol, aetherTransport = "h2", aetherEndpointCount = count,
            aetherMasqueSni = sni, aetherScanNetwork = network,
        ), null)

    @Test
    fun `thirteen profiles reuse existing keys and share only two protocol scans`() = runTest {
        readyToScan()
        val candidates = (1..5).map { "h2@192.0.2.$it:443" }
        whenever(editor.scan(any(), any())).thenReturn(AetherScanResult(endpoint, candidates = candidates))
        val stored = mutableListOf<ProfileItem>()
        val importer = QuickAetherProfile(keys, editor, profiles, storeImported = { stored.add(it) })
        val links = (1..10).map { setupLink("masque-$it", count = if (it == 1) 5 else 2) } +
            (1..3).map { setupLink("wg-$it", "wg") }
        assertEquals(QuickAetherProfile.ImportResult(13), importer.importLinks(links, "_andromeda_free") {})
        verifyNoInteractions(keys)
        verify(editor, times(2)).scan(any(), any())
        verify(editor).scan(argThat { aetherProtocol == "masque" && aetherEndpointCount == 5 }, any())
        assertEquals(5, stored.first().aetherEndpoints?.size)
        assertEquals(2, stored[1].aetherEndpoints?.size)
        assertTrue(stored.all { it.subscriptionId == "_andromeda_free" })
        assertTrue(stored.all { it.server == endpoint.host })
    }

    @Test
    fun `different SNI or scan network never shares an incompatible result`() = runTest {
        readyToScan()
        whenever(editor.scan(any(), any())).thenReturn(AetherScanResult(endpoint))
        val importer = QuickAetherProfile(keys, editor, profiles, storeImported = { true })
        val links = listOf(setupLink("a", sni = "one.example", network = "tcp"),
            setupLink("b", sni = "two.example", network = "tcp"),
            setupLink("c", sni = "one.example", network = "udp"))
        assertEquals(3, importer.importLinks(links) {}.count)
        verify(editor, times(3)).scan(any(), any())
        verifyNoInteractions(keys)
    }

    @Test
    fun `normal refresh after a bolt always scans balanced without replacing keys`() = runTest {
        val old = ProfileItem.create(EConfigType.AETHER).copy(aetherScanMode = "ironclad", aetherEndpointCount = 5)
        whenever(profiles.loadProfile("card")).thenReturn(old)
        readyToScan()
        whenever(editor.scan(any(), any())).thenReturn(AetherScanResult(endpoint))
        val refresher = QuickAetherProfile(keys, editor, profiles, storeRefreshed = { _, _, updated ->
            assertEquals("balanced", updated.aetherScanMode)
            assertEquals(5, updated.aetherEndpointCount)
            true
        })
        assertNull(refresher.refresh("card") {})
        verify(editor).scan(argThat { aetherScanMode == "balanced" }, any())
        verifyNoInteractions(keys)
        assertEquals("ironclad", old.aetherScanMode)
    }

    @Test
    fun `global refresh scans each pool once and commits preserved profiles together`() = runTest {
        readyToScan()
        val originals = (1..4).associate { i -> "$i" to ProfileItem.create(EConfigType.AETHER).copy(
            remarks = "card-$i", aetherProtocol = if (i < 4) "masque" else "wg",
            aetherScanMode = "ironclad", aetherEndpointCount = i,
        ) }
        originals.forEach { (guid, profile) -> whenever(profiles.loadProfile(guid)).thenReturn(profile) }
        whenever(profiles.loadProfile("ordinary")).thenReturn(ProfileItem.create(EConfigType.VLESS))
        whenever(editor.scan(any(), any())).thenReturn(AetherScanResult(endpoint))
        var commits = 0
        val refresher = QuickAetherProfile(keys, editor, profiles, storeBatchRefreshed = { updates ->
            commits++
            assertEquals(originals.keys, updates.keys)
            updates.forEach { (guid, pair) ->
                assertEquals(originals[guid], pair.first)
                assertEquals(pair.first.remarks, pair.second.remarks)
                assertEquals(pair.first.aetherEndpointCount, pair.second.aetherEndpointCount)
                assertEquals("turbo", pair.second.aetherScanMode)
                assertEquals("both", pair.second.aetherIpVersion)
                assertEquals(endpoint.host, pair.second.server)
            }
            true
        })
        assertEquals(QuickAetherProfile.RefreshResult(originals.keys.toList()), refresher.refreshAll(originals.keys.toList() + "ordinary" + "1") {})
        assertEquals(1, commits)
        verify(editor, times(2)).scan(any(), any())
        verifyNoInteractions(keys)
    }

    @Test
    fun `global refresh empty missing keys failed scan rejected write and cancellation do not publish partial updates`() = runTest {
        var commits = 0
        val refresher = QuickAetherProfile(keys, editor, profiles, storeBatchRefreshed = { commits++; false })
        assertEquals(R.string.aether_refresh_none, refresher.refreshAll(emptyList()) {}.error)
        val old = ProfileItem.create(EConfigType.AETHER)
        whenever(profiles.loadProfile("a")).thenReturn(old)
        whenever(editor.missingKeys(any())).thenReturn(listOf("missing"))
        assertEquals(R.string.aether_scan_failed, refresher.refreshAll(listOf("a")) {}.error)
        readyToScan()
        assertEquals(R.string.aether_scan_failed, refresher.refreshAll(listOf("a")) {}.error)
        assertEquals(0, commits)
        whenever(editor.scan(any(), any())).thenReturn(AetherScanResult(endpoint))
        assertEquals(R.string.toast_failure, refresher.refreshAll(listOf("a")) {}.error)
        assertEquals(1, commits)
        whenever(editor.scan(any(), any())).thenThrow(kotlinx.coroutines.CancellationException("stop"))
        var cancelled = false
        try { refresher.refreshAll(listOf("a")) {} } catch (_: kotlinx.coroutines.CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(1, commits)
        verifyNoInteractions(keys)
    }

    @Test
    fun `global refresh cannot scan while another VPN remains active`() = runTest {
        whenever(profiles.loadProfile("a")).thenReturn(ProfileItem.create(EConfigType.AETHER))
        val refresher = QuickAetherProfile(keys, editor, profiles, prepareScanNetwork = { false },
            storeBatchRefreshed = { error("No updates allowed") })
        assertEquals(R.string.aether_scan_disconnect_vpn, refresher.refreshAll(listOf("a")) {}.error)
        verifyNoInteractions(keys, editor)
    }

    @Test
    fun `batch with a missing identity renews once then scans once for every matching profile`() = runTest {
        readyToScan()
        whenever(editor.missingKeys(any())).thenReturn(listOf("missing"))
        whenever(editor.scan(any(), any())).thenReturn(AetherScanResult(endpoint))
        val stored = mutableListOf<ProfileItem>()
        val importer = QuickAetherProfile(keys, editor, profiles, storeImported = { stored.add(it) })
        assertEquals(10, importer.importLinks((1..10).map { setupLink("card-$it") }) {}.count)
        verify(keys).renew(eq(AetherKeyKind.MASQUE), any(), any(), any())
        verify(editor).scan(any(), any())
        assertEquals(10, stored.size)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `global scan ignores per card filters and shares one ninety second pool per protocol`() = runTest {
        readyToScan()
        val a = ProfileItem.create(EConfigType.AETHER).copy(aetherProtocol = "masque", aetherMasqueSni = "one.example", aetherIpVersion = "4")
        val b = a.copy(aetherMasqueSni = "two.example", aetherScanNetwork = "tcp", aetherScanMode = "ironclad")
        whenever(profiles.loadProfile("a")).thenReturn(a)
        whenever(profiles.loadProfile("b")).thenReturn(b)
        val scanner = object : AetherEditorSource by editor {
            override suspend fun scanMany(profile: ProfileItem, onOutput: (String) -> Unit, onCandidates: (List<String>) -> Unit): AetherScanResult? {
                assertEquals("turbo", profile.aetherScanMode)
                assertEquals("both", profile.aetherIpVersion)
                assertEquals("tcp+udp", profile.aetherScanNetwork)
                assertNull(profile.aetherMasqueSni)
                onCandidates(listOf("h2@192.0.2.1:443"))
                kotlinx.coroutines.awaitCancellation()
            }
        }
        var writes = 0
        val refresh = QuickAetherProfile(keys, scanner, profiles, storeBatchRefreshed = { updates ->
            writes++; assertEquals(setOf("a", "b"), updates.keys); true
        })
        val started = testScheduler.currentTime
        assertEquals(2, refresh.refreshAll(listOf("a", "b")) {}.guids.size)
        assertEquals(90_000L, testScheduler.currentTime - started)
        assertEquals(1, writes)
        verifyNoInteractions(keys)
    }

    @Test
    fun `failed protocol does not discard a healthy protocol and stops at ninety seconds`() = runTest {
        readyToScan()
        whenever(profiles.loadProfile("wg")).thenReturn(ProfileItem.create(EConfigType.AETHER).copy(aetherProtocol = "wg"))
        whenever(profiles.loadProfile("masque")).thenReturn(ProfileItem.create(EConfigType.AETHER).copy(aetherProtocol = "masque"))
        val scanner = object : AetherEditorSource by editor {
            override suspend fun scanMany(profile: ProfileItem, onOutput: (String) -> Unit, onCandidates: (List<String>) -> Unit): AetherScanResult? {
                if (profile.aetherProtocol == "masque") kotlinx.coroutines.awaitCancellation()
                assertEquals("udp", profile.aetherScanNetwork)
                return AetherScanResult(endpoint)
            }
        }
        val refresh = QuickAetherProfile(keys, scanner, profiles, storeBatchRefreshed = { updates ->
            assertEquals(setOf("wg"), updates.keys); true
        })
        val result = refresh.refreshAll(listOf("masque", "wg")) {}
        assertEquals(listOf("wg"), result.guids)
        assertEquals(R.string.aether_scan_failed, result.error)
    }

    @Test
    fun `restored profiles request preparation only when protocol identity files are missing`() = runTest {
        whenever(profiles.loadProfile("a")).thenReturn(ProfileItem.create(EConfigType.AETHER))
        whenever(editor.missingKeys(any())).thenReturn(listOf("missing"))
        assertTrue(creator.needsKeys("a"))
        whenever(editor.missingKeys(any())).thenReturn(emptyList())
        assertFalse(creator.needsKeys("a"))
        whenever(profiles.loadProfile("other")).thenReturn(ProfileItem.create(EConfigType.VLESS))
        assertFalse(creator.needsKeys("other"))
        assertFalse(creator.needsKeys("removed"))
        verifyNoInteractions(keys)
    }

    private suspend fun readyToScan() {
        whenever(editor.scanMany(any(), any(), any())).thenAnswer { invocation ->
            runBlocking { editor.scan(invocation.getArgument(0), invocation.getArgument(1)) }
        }
        whenever(editor.missingKeys(any())).thenReturn(emptyList())
        whenever(keys.isCoreAvailable()).thenReturn(true)
        whenever(keys.renew(any(), any(), any(), any())).thenReturn(listOf(AetherKey("aether.toml", AetherIdentity("id", "", ""))))
    }
}
