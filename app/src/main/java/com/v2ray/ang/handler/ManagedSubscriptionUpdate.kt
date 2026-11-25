package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.fmt.AetherSetupLink
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.main.QuickAetherProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object ManagedSubscriptionUpdate {
    val lock = Mutex()

    fun handles(id: String): Boolean = id == AppConfig.ANDROMEDA_SUBSCRIPTION_ID || proGroupNumber(id) > 0

    suspend fun refresh(context: Context, id: String): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val sub = MmkvManager.decodeSubscription(id) ?: return@withContext true
            if (!sub.enabled) return@withContext true
            try {
                val repository = ControlPanelRepository(context.applicationContext)
                val content = if (id == AppConfig.ANDROMEDA_SUBSCRIPTION_ID) {
                    repository.loadFreeConfigurationCancellable()
                } else {
                    val token = ProSubscriptionStore.tokenFor(id) ?: return@withContext true
                    repository.loadPro(token = token).content
                } ?: return@withContext false
                val (setup, ordinary) = AetherSetupLink.splitImport(content)
                val previous = MmkvManager.decodeServerList(id).toSet()
                var committed = false
                try {
                    if (setup.isNotEmpty()) {
                        val scanned = QuickAetherProfile(context, allowVpnDisconnect = false).importLinks(setup, id) {}
                        if (scanned.count != setup.size) return@withContext false
                    }
                    val regular = if (ordinary.isBlank()) 0 else AngConfigManager.importProfilesOnly(ordinary, id)
                    if (ordinary.isNotBlank() && regular == 0) return@withContext false
                    committed = MmkvManager.finalizeManagedImport(id, previous, commit = true)
                    if (committed) {
                        MmkvManager.trySetSubscriptionUpdated(id, System.currentTimeMillis())
                        MessageHelper.sendMsg2UI(context, AppConfig.MSG_SERVERS_CHANGED, id)
                    }
                    committed
                } finally {
                    if (!committed) withContext(NonCancellable + Dispatchers.IO) {
                        MmkvManager.finalizeManagedImport(id, previous, commit = false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }
    }
}
