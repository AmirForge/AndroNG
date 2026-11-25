package com.v2ray.ang.handler

import com.v2ray.ang.AngApplication
import com.v2ray.ang.core.soar.SoarImport
import com.v2ray.ang.core.soar.SoarResolverProfile
import com.v2ray.ang.core.soar.SoarStore
import com.v2ray.ang.util.JsonUtil

/** One MMKV record commits profiles, selections and resolver lists atomically. */
object SoarRepository {
    private const val KEY = "soar_workspace_v1"
    fun isBuiltInResolver(id: String): Boolean =
        id == "dns-connect-regional-default" || id == "soar-public-default"
    private val builtInResolvers by lazy {
        listOf(
            readResolverProfile("dns-connect-regional-default", "Regional DNS", "dns-connect-regional-resolvers.txt"),
            readResolverProfile("soar-public-default", "Soar DNS", "soar-default-resolvers.txt"),
        )
    }

    private fun readResolverProfile(id: String, name: String, asset: String): SoarResolverProfile {
        val addresses = AngApplication.application.assets.open(asset).bufferedReader()
            .use { SoarImport.resolvers(it.readText()) }
        return SoarResolverProfile(id = id, name = name, addresses = addresses)
    }

    fun load(): SoarStore {
        val stored = MmkvManager.decodeSettingsString(KEY)?.let {
            JsonUtil.fromJsonSafe(it, SoarStore::class.java)
        } ?: SoarStore()
        val builtInIds = builtInResolvers.mapTo(hashSetOf()) { it.id }
        val profiles = builtInResolvers + stored.resolverProfiles.filterNot { it.id in builtInIds || it.id.startsWith("soar-") && it.id.endsWith("-default") }
        return stored.copy(
            resolverProfiles = profiles,
            resolverId = stored.resolverId.takeIf { id -> profiles.any { it.id == id } }
                ?: builtInResolvers.first().id,
        )
    }

    fun save(store: SoarStore) {
        val builtInIds = builtInResolvers.mapTo(hashSetOf()) { it.id }
        val customProfiles = store.resolverProfiles.filterNot { it.id in builtInIds }
        check(MmkvManager.encodeSettings(KEY, JsonUtil.toJson(store.copy(resolverProfiles = customProfiles))))
    }
}
