package com.v2ray.ang.ui.soar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.core.soar.*
import com.v2ray.ang.handler.SoarRepository
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SoarUiState(
    val store: SoarStore = SoarStore(),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val selecting: Boolean = false,
    val error: Int? = null,
    val phase: String = "idle",
    val resolver: String = "",
    val attempt: Int = 0,
    val total: Int = 0,
    val permissionRequest: Int = 0,
    val importedText: String? = null,
    val saveRevision: Int = 0,
) {
    val busy get() = phase in listOf("checking", "discovery", "starting")
    val connected get() = phase == "connected"
    val locked get() = busy || connected || saving
}

sealed interface SoarAction {
    data object ImportFree : SoarAction
    data class Import(val link: String) : SoarAction
    data class Manual(val name: String, val zone: String, val key: String) : SoarAction
    data class Select(val id: String) : SoarAction
    data class Remove(val id: String) : SoarAction
    data class SaveResolvers(val name: String, val text: String, val id: String? = null) : SoarAction
    data class SelectResolvers(val id: String) : SoarAction
    data class RemoveResolvers(val id: String) : SoarAction
    data class ReadFile(val uri: Uri) : SoarAction
    data class SaveSettingsProfile(val settings: SoarSettings, val runtime: SoarRuntimeSettings) : SoarAction
    data class ApplySettingsProfile(val id: String) : SoarAction
    data class Settings(val value: SoarSettings, val runtime: SoarRuntimeSettings? = null) : SoarAction
    data object Reset : SoarAction
    data object Connect : SoarAction
    data class Permission(val granted: Boolean) : SoarAction
    data object Stop : SoarAction
    data object Refresh : SoarAction
    data object ClearError : SoarAction
    data object ClearFile : SoarAction
}

class SoarViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(SoarUiState())
    val state = mutable.asStateFlow()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val phase = intent.getStringExtra("phase") ?: return
            mutable.update { it.copy(phase = phase, resolver = intent.getStringExtra("resolver").orEmpty(),
                attempt = intent.getIntExtra("attempt", 0), total = intent.getIntExtra("total", 0),
                error = if (phase == "failed") R.string.soar_connection_failed else it.error) }
        }
    }

    init {
        ContextCompat.registerReceiver(application, receiver, IntentFilter(MessageHelper.SOAR_STATE), Utils.receiverFlags())
        viewModelScope.launch(Dispatchers.IO) {
            mutable.update { it.copy(store = SoarRepository.load(), loaded = true) }
            MessageHelper.sendSoarCommand(application, "query")
        }
    }

    fun act(action: SoarAction) {
        when (action) {
            SoarAction.ImportFree -> importFree()
            SoarAction.Refresh -> MessageHelper.sendSoarCommand(getApplication(), "query")
            SoarAction.ClearError -> mutable.update { it.copy(error = null) }
            SoarAction.ClearFile -> mutable.update { it.copy(importedText = null) }
            SoarAction.Stop -> MessageHelper.sendSoarCommand(getApplication(), "stop")
            SoarAction.Connect -> {
                val s = mutable.value
                val p = s.store.profiles.firstOrNull { it.id == s.store.selectedId }
                val dns = s.store.resolverProfiles.firstOrNull { it.id == s.store.resolverId }?.addresses.orEmpty()
                if (p == null || !p.valid() || (dns.isEmpty() && (p.engine != DnsTunnelEngine.SOAR || !p.settings.discovery))) {
                    mutable.update { it.copy(error = R.string.soar_select_profile) }
                } else if (!s.locked) mutable.update { it.copy(permissionRequest = it.permissionRequest + 1) }
            }
            is SoarAction.Permission -> {
                if (!action.granted) mutable.update { it.copy(error = R.string.soar_permission_denied, permissionRequest = 0) }
                else {
                    mutable.update { it.copy(phase = "starting", error = null, permissionRequest = 0) }
                    try { LauncherManager.startSoar(getApplication()) }
                    catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "Soar foreground start failed", e)
                        mutable.update { it.copy(phase = "failed", error = R.string.soar_connection_failed) }
                    }
                }
            }
            is SoarAction.ReadFile -> viewModelScope.launch(Dispatchers.IO) {
                try {
                    val text = getApplication<Application>().contentResolver.openInputStream(action.uri)?.use {
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = it.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 1_000_000)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray().toString(Charsets.UTF_8)
                    } ?: error("missing file")
                    mutable.update { it.copy(importedText = text) }
                } catch (e: Exception) {
                    LogUtil.w(AppConfig.TAG, "Soar resolver file read failed", IllegalArgumentException(e.javaClass.simpleName))
                    mutable.update { it.copy(error = R.string.soar_invalid_resolvers) }
                }
            }
            else -> mutate(action)
        }
    }

    private fun importFree() {
        if (!mutable.value.loaded || mutable.value.locked) return
        mutable.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val repository = com.v2ray.ang.handler.ControlPanelRepository(getApplication())
                val links = repository.loadFreeDns()
                if (links.isEmpty()) {
                    mutable.update { it.copy(error = R.string.dns_free_unavailable) }
                    return@launch
                }
                val updated = withContext(Dispatchers.IO) {
                    val imported = links.fold(mutable.value.store) { store, link -> updateSoarStore(store, SoarAction.Import(link)) }
                    SoarRepository.save(imported)
                    imported
                }
                mutable.update { it.copy(store = updated, saveRevision = it.saveRevision + 1) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(error = R.string.dns_free_unavailable) }
            } finally {
                mutable.update { it.copy(saving = false) }
            }
        }
    }

    private fun mutate(action: SoarAction) {
        if (mutable.value.locked || !mutable.value.loaded) return
        val selecting = action is SoarAction.Select || action is SoarAction.SelectResolvers
        mutable.update { it.copy(saving = true, selecting = selecting, error = null) }
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.Default) { updateSoarStore(mutable.value.store, action) }
                withContext(Dispatchers.IO) { SoarRepository.save(updated) }
                mutable.update { it.copy(store = updated, saving = false, selecting = false, saveRevision = it.saveRevision + 1) }
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Soar profile operation failed", IllegalArgumentException(e.javaClass.simpleName))
                mutable.update { it.copy(saving = false, selecting = false, error = when (action) {
                    is SoarAction.SaveResolvers -> R.string.soar_invalid_resolvers
                    is SoarAction.Settings -> R.string.soar_invalid_settings
                    else -> R.string.soar_invalid_profile
                }) }
            }
        }
    }

    override fun onCleared() { getApplication<Application>().unregisterReceiver(receiver) }
}

    internal fun updateSoarStore(store: SoarStore, action: SoarAction): SoarStore = when (action) {
        is SoarAction.Import -> {
            val (p, addresses) = SoarImport.profile(action.link)
            val existing = store.profiles.firstOrNull {
                it.engine == p.engine && it.zone.equals(p.zone, true) &&
                    it.publicKey == p.publicKey && it.encryptionMethod == p.encryptionMethod
            }
            val dns = SoarResolverProfile(name = p.name, addresses = addresses)
            if (existing != null) store.copy(selectedId = existing.id)
            else store.copy(profiles = store.profiles + p, selectedId = p.id,
                resolverProfiles = if (addresses.isEmpty()) store.resolverProfiles else store.resolverProfiles + dns,
                resolverId = if (addresses.isEmpty()) store.resolverId else dns.id)
        }
        is SoarAction.Manual -> {
            val p = SoarProfile(name = action.name.trim(), zone = action.zone.trim().trimEnd('.'), publicKey = action.key.trim())
            require(p.valid())
            store.copy(profiles = store.profiles + p, selectedId = p.id)
        }
        is SoarAction.Select -> { require(store.profiles.any { it.id == action.id }); store.copy(selectedId = action.id) }
        is SoarAction.Remove -> {
            val remaining = store.profiles.filterNot { it.id == action.id }
            store.copy(profiles = remaining, selectedId = if (store.selectedId == action.id) remaining.firstOrNull()?.id.orEmpty() else store.selectedId)
        }
        is SoarAction.SaveResolvers -> {
            val addresses = SoarImport.resolvers(action.text)
            require(action.name.isNotBlank() && addresses.isNotEmpty())
            val p = SoarResolverProfile(id = action.id ?: java.util.UUID.randomUUID().toString(), name = action.name.trim(), addresses = addresses)
            store.copy(resolverProfiles = store.resolverProfiles.filterNot { it.id == p.id } + p, resolverId = p.id)
        }
        is SoarAction.SelectResolvers -> { require(store.resolverProfiles.any { it.id == action.id }); store.copy(resolverId = action.id) }
        is SoarAction.RemoveResolvers -> {
            val remaining = store.resolverProfiles.filterNot { it.id == action.id }
            store.copy(resolverProfiles = remaining, resolverId = if (store.resolverId == action.id) remaining.firstOrNull()?.id.orEmpty() else store.resolverId)
        }
        is SoarAction.SaveSettingsProfile -> {
            require(action.settings.valid() && action.runtime.valid())
            val name = action.runtime.name.trim()
            val previous = store.settingsProfiles.firstOrNull { it.name == name }
            val preset = SoarSettingsProfile(previous?.id ?: java.util.UUID.randomUUID().toString(),
                name, action.settings, action.runtime.copy(name = name, username = "", password = "", authentication = false))
            store.copy(settingsProfiles = store.settingsProfiles.filterNot { it.id == preset.id } + preset)
        }
        is SoarAction.ApplySettingsProfile -> {
            val preset = requireNotNull(store.settingsProfiles.firstOrNull { it.id == action.id })
            updateSoarStore(store, SoarAction.Settings(preset.settings, preset.runtime))
        }
        is SoarAction.Settings, SoarAction.Reset -> {
            val settings = (action as? SoarAction.Settings)?.value ?: SoarSettings()
            val runtime = (action as? SoarAction.Settings)?.runtime
            require(settings.valid() && (runtime == null || runtime.valid()) && store.profiles.any { it.id == store.selectedId })
            store.copy(profiles = store.profiles.map { if (it.id == store.selectedId) it.copy(settings = settings,
                runtime = if (action == SoarAction.Reset) SoarRuntimeSettings() else runtime ?: it.runtime) else it })
        }
        else -> store
    }
