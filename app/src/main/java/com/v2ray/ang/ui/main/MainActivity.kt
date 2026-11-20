package com.v2ray.ang.ui.main

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.fmt.AetherSetupLink
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionInfoParser
import com.v2ray.ang.ui.AboutActivity
import com.v2ray.ang.ui.backup.BackupActivity
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.logcat.LogcatActivity
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.ui.routing.RoutingSettingActivity
import com.v2ray.ang.ui.server.ProfileEditorResult
import com.v2ray.ang.ui.server.ServerAetherActivity
import com.v2ray.ang.ui.server.ServerCustomConfigActivity
import com.v2ray.ang.ui.server.ServerGroupActivity
import com.v2ray.ang.ui.server.ServerHttpActivity
import com.v2ray.ang.ui.server.ServerHysteria2Activity
import com.v2ray.ang.ui.server.ServerMasqueActivity
import com.v2ray.ang.ui.server.ServerProxyChainActivity
import com.v2ray.ang.ui.server.ServerShadowsocksActivity
import com.v2ray.ang.ui.server.ServerSocksActivity
import com.v2ray.ang.ui.server.ServerTrojanActivity
import com.v2ray.ang.ui.server.ServerVlessActivity
import com.v2ray.ang.ui.server.ServerVmessActivity
import com.v2ray.ang.ui.server.ServerWireguardActivity
import com.v2ray.ang.ui.settings.SettingsActivity
import com.v2ray.ang.ui.subscription.SubSettingActivity
import com.v2ray.ang.ui.userasset.UserAssetActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseComponentActivity() {

    companion object {
        private const val EXTRA_AETHER_IMPORT = "com.v2ray.ang.extra.AETHER_IMPORT"

        internal fun aetherImportIntent(context: Context, text: String): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_AETHER_IMPORT, text)
    }

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application, MainRepository(application as AngApplication))
    }

    private var connectionPreflightJob: Job? = null
    private var connectionPreflightGeneration = 0L

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startV2Ray()
        }

    private val profileEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val action = data.getStringExtra(ProfileEditorResult.EXTRA_ACTION)
                ?: return@registerForActivityResult
            if (action != ProfileEditorResult.ACTION_SAVED &&
                action != ProfileEditorResult.ACTION_DELETED
            ) return@registerForActivityResult
            val restartService = data.getBooleanExtra(
                ProfileEditorResult.EXTRA_RESTART_SERVICE, false
            )
            val selectedProfileSaved = action == ProfileEditorResult.ACTION_SAVED &&
                    data.getStringExtra(ProfileEditorResult.EXTRA_GUID) == mainViewModel.uiState.value.selectedGuid
            mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService || selectedProfileSaved) LauncherManager.restartService(this)
        }

    private val settingsActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val restartService = SettingsChangeManager.consumeRestartService()
            val refreshGroups = SettingsChangeManager.consumeSetupGroupTab()
            mainViewModel.refreshUiSettings()
            if (refreshGroups) mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService) LauncherManager.restartService(this)
        }

    // Keep existing launcher registration order stable when restoring a pending Activity result.
    private val preparedAetherVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startPreparedAetherConnection()
            else mainViewModel.onAction(MainAction.CancelPreparedAetherConnection)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainViewModel.onAction(MainAction.Initialize)
        if (savedInstanceState == null) importSharedAether(intent)

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importSharedAether(intent)
    }

    private fun importSharedAether(intent: Intent) {
        val text = intent.getStringExtra(EXTRA_AETHER_IMPORT) ?: return
        intent.removeExtra(EXTRA_AETHER_IMPORT)
        mainViewModel.onAction(MainAction.StageExternalImport(text))
    }

    override fun onStart() {
        super.onStart()
        // The service can have gone away while the screen was not shown, without a word if its process died.
        mainViewModel.onAction(MainAction.RefreshServiceState)
        mainViewModel.onAction(MainAction.RefreshControlPanel)
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }
        MainScreen(
            mainViewModel = mainViewModel,
            onAction = { action ->
                when (action) {
                    MainAction.BuyProCode -> openContact("v2medaCharge_bot")
                    MainAction.ToggleService -> handleFabAction()
                    MainAction.TestCurrentServer -> handleLayoutTestClick()
                    MainAction.ImportQRcode -> importQRcode()
                    MainAction.ImportClipboard -> importClipboard()
                    MainAction.ImportConfigLocal -> importConfigLocal()
                    is MainAction.ImportManually -> importManually(action.type)
                    MainAction.RestartService -> requestServiceStart(restart = true)
                    MainAction.LocateSelectedServer -> mainViewModel.triggerLocateSelectedServer()
                    is MainAction.SelectServer -> setSelectServer(action.guid)
                    is MainAction.ConnectPreparedAether -> requestPreparedAetherConnection(action.guid)
                    is MainAction.EditServer -> editServer(action.guid, action.profile)
                    is MainAction.ShareClipboard -> {
                        if (!shareToClipboard(action.guid)) toastError(R.string.toast_failure)
                    }
                    is MainAction.ShareFullContent -> shareFullContentAsync(action.guid)
                    is MainAction.OpenSubscriptionLink -> openSubscriptionLink(action.url)
                    is MainAction.OpenContact -> openContact(action.username)
                    else -> mainViewModel.onAction(action)
                }
            },
            onNavigate = { route -> navigateTo(route) },
        )
    }

    private fun shareToClipboard(guid: String): Boolean =
        AngConfigManager.share2Clipboard(this, guid) == 0

    private fun shareFullContentAsync(guid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(this@MainActivity, guid)
            withContext(Dispatchers.Main) {
                if (result == 0) toastSuccess(R.string.toast_success)
                else toastError(R.string.toast_failure)
            }
        }
    }

    private fun openSubscriptionLink(url: String) {
        // Links come from a remote panel, so only http(s) and tg:// are ever handed to ACTION_VIEW.
        val safeUrl = SubscriptionInfoParser.sanitizeLink(url) ?: return
        Utils.openUri(this, safeUrl)
    }

    private fun openContact(username: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "tg://resolve?domain=$username".toUri()))
        } catch (_: ActivityNotFoundException) {
            Utils.openUri(this, "https://t.me/$username")
        }
    }

    private fun navigateTo(destination: MainDestination) {
        val intent = when (destination) {
            MainDestination.Subscriptions -> Intent(this, SubSettingActivity::class.java)
            MainDestination.PerAppProxy -> Intent(this, PerAppProxyActivity::class.java)
            MainDestination.Routing -> Intent(this, RoutingSettingActivity::class.java)
            MainDestination.UserAssets -> Intent(this, UserAssetActivity::class.java)
            MainDestination.Settings -> Intent(this, SettingsActivity::class.java)
            MainDestination.Logcat -> Intent(this, LogcatActivity::class.java)
            MainDestination.BackupRestore -> Intent(this, BackupActivity::class.java)
            MainDestination.About -> Intent(this, AboutActivity::class.java)
        }
        settingsActivityLauncher.launch(intent)
    }

    private fun handleFabAction() {
        connectionPreflightGeneration++
        connectionPreflightJob?.cancel()
        if (mainViewModel.uiState.value.isCreatingAether) return
        if (mainViewModel.uiState.value.isRunning) {
            LauncherManager.stopService(this)
            // A service whose process died takes no stop and answers nothing; asking for its state right
            // after lets that silence show the screen as stopped instead of leaving it connected.
            mainViewModel.onAction(MainAction.RefreshServiceState)
        } else {
            requestServiceStart()
        }
    }

    private fun requestPreparedAetherConnection(guid: String) {
        if (!mainViewModel.claimPreparedAetherConnection(guid)) return
        try {
            val permission = if (SettingsManager.isVpnMode()) VpnService.prepare(this) else null
            if (permission == null) startPreparedAetherConnection() else preparedAetherVpnPermission.launch(permission)
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Aether auto-connect permission failed guid=$guid", error)
            mainViewModel.onAction(MainAction.CancelPreparedAetherConnection)
            toastError(R.string.toast_services_failure)
        }
    }

    private fun startPreparedAetherConnection() {
        if (!mainViewModel.beginPreparedAetherConnection()) return
        val preparation = mainViewModel.uiState.value.aetherProgress?.startedAt
        try {
            LauncherManager.restartServiceOrStart(this) {
                // The restart acknowledgement can arrive after a different card was selected.
                if (mainViewModel.isPreparedAetherConnectionCurrent() &&
                    mainViewModel.uiState.value.aetherProgress?.startedAt == preparation) {
                    try {
                        startV2Ray()
                    } catch (error: Exception) {
                        LogUtil.e(AppConfig.TAG, "Aether auto-connect start failed guid=${mainViewModel.uiState.value.selectedGuid}", error)
                        mainViewModel.onAction(MainAction.CancelPreparedAetherConnection)
                        toastError(R.string.toast_services_failure)
                    }
                }
            }
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Aether auto-connect dispatch failed guid=${mainViewModel.uiState.value.selectedGuid}", error)
            mainViewModel.onAction(MainAction.CancelPreparedAetherConnection)
            toastError(R.string.toast_services_failure)
        }
    }

    private fun requestServiceStart(restart: Boolean = false) {
        connectionPreflightJob?.cancel()
        val guid = mainViewModel.uiState.value.selectedGuid ?: return
        val generation = ++connectionPreflightGeneration
        connectionPreflightJob = lifecycleScope.launch {
            try {
                val missing = mainViewModel.needsWarpKeys(guid)
                if (generation != connectionPreflightGeneration || mainViewModel.uiState.value.selectedGuid != guid) return@launch
                if (missing) {
                    // Reuse the card bolt owner: fresh WARP, endpoint scan, existing feedback, then acknowledged start.
                    mainViewModel.onAction(MainAction.RefreshAetherProfile(guid, true))
                } else if (restart) LauncherManager.restartServiceOrStart(this@MainActivity) {
                    if (generation == connectionPreflightGeneration && mainViewModel.uiState.value.selectedGuid == guid && !isFinishing && !isDestroyed)
                        requestNormalServiceStart()
                } else requestNormalServiceStart()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Connection key preflight failed guid=$guid", error)
                toastError(R.string.toast_services_failure)
            }
        }
    }

    private fun requestNormalServiceStart() {
        if (!SettingsManager.isVpnMode()) {
            startV2Ray()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) startV2Ray() else requestVpnPermission.launch(intent)
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.uiState.value.isRunning) {
            mainViewModel.testCurrentServerRealPing()
        }
    }

    private fun startV2Ray() {
        if (mainViewModel.uiState.value.selectedGuid.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        if (Build.VERSION.SDK_INT >= 37) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        LauncherManager.startService(this)
    }

    private fun importManually(createConfigType: Int) {
        val intent = when (createConfigType) {
            EConfigType.POLICYGROUP.value -> Intent(this, ServerGroupActivity::class.java)
            EConfigType.PROXYCHAIN.value -> Intent(this, ServerProxyChainActivity::class.java)
            EConfigType.VMESS.value -> Intent(this, ServerVmessActivity::class.java)
            EConfigType.VLESS.value -> Intent(this, ServerVlessActivity::class.java)
            EConfigType.SHADOWSOCKS.value -> Intent(this, ServerShadowsocksActivity::class.java)
            EConfigType.SOCKS.value -> Intent(this, ServerSocksActivity::class.java)
            EConfigType.HTTP.value -> Intent(this, ServerHttpActivity::class.java)
            EConfigType.TROJAN.value -> Intent(this, ServerTrojanActivity::class.java)
            EConfigType.WIREGUARD.value -> Intent(this, ServerWireguardActivity::class.java)
            EConfigType.HYSTERIA2.value -> Intent(this, ServerHysteria2Activity::class.java)
            EConfigType.MASQUE.value -> Intent(this, ServerMasqueActivity::class.java)
            EConfigType.AETHER.value -> Intent(this, ServerAetherActivity::class.java)
            else -> Intent(this, ServerHttpActivity::class.java).apply {
                putExtra("createConfigType", createConfigType)
            }
        }.apply {
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                mainViewModel.onAction(MainAction.ImportBatchConfig(scanResult))
            }
        }
    }

    private fun importClipboard() {
        try {
            val text = Utils.getClipboard(this)
            mainViewModel.onAction(MainAction.ImportBatchConfig(text))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
        }
    }

    private fun importConfigLocal() {
        launchFileChooser { uri ->
            if (uri == null) return@launchFileChooser
            try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    mainViewModel.onAction(MainAction.ImportBatchConfig(reader.readText()))
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
            }
        }
    }

    private fun editServer(guid: String, profile: ProfileItem) {
        val activityClass = when (profile.configType) {
            EConfigType.CUSTOM, EConfigType.CUSTOM_OUTBOUND -> ServerCustomConfigActivity::class.java
            EConfigType.POLICYGROUP -> ServerGroupActivity::class.java
            EConfigType.PROXYCHAIN -> ServerProxyChainActivity::class.java
            EConfigType.VMESS -> ServerVmessActivity::class.java
            EConfigType.VLESS -> ServerVlessActivity::class.java
            EConfigType.SHADOWSOCKS -> ServerShadowsocksActivity::class.java
            EConfigType.SOCKS -> ServerSocksActivity::class.java
            EConfigType.HTTP -> ServerHttpActivity::class.java
            EConfigType.TROJAN -> ServerTrojanActivity::class.java
            EConfigType.WIREGUARD -> ServerWireguardActivity::class.java
            EConfigType.HYSTERIA2 -> ServerHysteria2Activity::class.java
            EConfigType.MASQUE -> ServerMasqueActivity::class.java
            EConfigType.AETHER -> ServerAetherActivity::class.java
            else -> ServerHttpActivity::class.java
        }
        val intent = Intent(this, activityClass).apply {
            putExtra("guid", guid)
            putExtra("isRunning", mainViewModel.uiState.value.isRunning)
            putExtra("createConfigType", profile.configType.value)
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun setSelectServer(guid: String) {
        val selected = mainViewModel.uiState.value.selectedGuid
        if (guid != selected) {
            connectionPreflightGeneration++
            connectionPreflightJob?.cancel()
            val running = mainViewModel.uiState.value.isRunning
            mainViewModel.updateSelectedGuid(guid)
            if (running) requestServiceStart(restart = true)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
