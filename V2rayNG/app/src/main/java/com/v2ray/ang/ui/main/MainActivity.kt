package com.v2ray.ang.ui.main

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.AboutActivity
import com.v2ray.ang.ui.backup.BackupActivity
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity
import com.v2ray.ang.ui.logcat.LogcatActivity
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.ui.routing.RoutingSettingActivity
import com.v2ray.ang.ui.server.ProfileEditorResult
import com.v2ray.ang.ui.server.ServerCustomConfigActivity
import com.v2ray.ang.ui.server.ServerGroupActivity
import com.v2ray.ang.ui.server.ServerHttpActivity
import com.v2ray.ang.ui.server.ServerHysteria2Activity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : HelperBaseComponentActivity() {

    private val SERVER_BASE_URL = "http://213.176.95.227:8088"

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application, MainRepository(application as AngApplication))
    }

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
            mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService && mainViewModel.uiState.value.isRunning) {
                restartV2Ray()
            }
        }

    private val settingsActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val restartService = SettingsChangeManager.consumeRestartService()
            val refreshGroups = SettingsChangeManager.consumeSetupGroupTab()
            mainViewModel.refreshUiSettings()
            if (refreshGroups) mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService && mainViewModel.uiState.value.isRunning) restartV2Ray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Автоматический импорт правил маршрутизации (Белый список РФ)
        val isRussianSetupDone = MmkvManager.decodeSettingsBool("is_russian_setup_done_v1")
        if (!isRussianSetupDone) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    SettingsManager.resetRoutingRulesetsFromPresets(this@MainActivity, 4)
                    MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, "AsIs")
                    MmkvManager.encodeSettings("is_russian_setup_done_v1", true)
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to auto-import predefined ruleset", e)
                }
            }
        }

        mainViewModel.onAction(MainAction.Initialize)

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}

        // Бесшовный запуск: если конфиг отсутствует — авторегистрация устройства
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1000)
            if (mainViewModel.uiState.value.selectedGuid.isNullOrEmpty()) {
                autoAuthenticateDevice()
            }
        }
    }

    private fun getOrCreateDeviceId(): String {
        var deviceId = MmkvManager.decodeSettingsString("dev_device_id")
        if (deviceId.isNullOrEmpty()) {
            deviceId = "QUEST-" + UUID.randomUUID().toString().substring(0, 8).uppercase()
            MmkvManager.encodeSettings("dev_device_id", deviceId)
        }
        return deviceId
    }

    private fun autoAuthenticateDevice() {
        val deviceId = getOrCreateDeviceId()
        toast("🚀 Подключение VR-шлема...")

        lifecycleScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("$SERVER_BASE_URL/api/vr/auth")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }

                val payload = JSONObject().apply {
                    put("device_id", deviceId)
                }

                OutputStreamWriter(connection.outputStream).use { it.write(payload.toString()) }

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val responseText = BufferedReader(InputStreamReader(stream)).use { it.readText() }
                val json = JSONObject(responseText)

                withContext(Dispatchers.Main) {
                    if (responseCode == 200 && json.optString("status") == "ok") {
                        val vlessConfig = json.getString("config")
                        mainViewModel.onAction(MainAction.ImportBatchConfig(vlessConfig))
                        toast("✅ Ключ активирован!")
                        lifecycleScope.launch {
                            kotlinx.coroutines.delay(1500)
                            handleFabAction()
                        }
                    } else {
                        val msg = json.optString("message", "Ошибка авторизации устройства")
                        toast("⚠️ $msg")
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Auto-Auth Error", e)
                withContext(Dispatchers.Main) {
                    toast("⚠️ Ошибка сервера: ${e.localizedMessage}")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun requestPaymentUrl(months: Int = 1) {
        val deviceId = getOrCreateDeviceId()
        toast("💳 Создание ссылки оплаты...")

        lifecycleScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("$SERVER_BASE_URL/api/vr/create-payment")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }

                val payload = JSONObject().apply {
                    put("device_id", deviceId)
                    put("months", months)
                }

                OutputStreamWriter(connection.outputStream).use { it.write(payload.toString()) }

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val responseText = BufferedReader(InputStreamReader(stream)).use { it.readText() }
                val json = JSONObject(responseText)

                withContext(Dispatchers.Main) {
                    val payUrl = json.optString("payment_url")
                    if (responseCode == 200 && payUrl.isNotEmpty()) {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(payUrl))
                        startActivity(browserIntent)
                    } else {
                        toast("⚠️ Не удалось получить ссылку на оплату")
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Payment URL Error", e)
                withContext(Dispatchers.Main) {
                    toast("⚠️ Ошибка сети: ${e.localizedMessage}")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    @Composable
    override fun ScreenContent() {
        MainScreen(
            mainViewModel = mainViewModel,
            onAction = { action ->
                when (action) {
                    MainAction.ToggleService -> handleFabAction()
                    MainAction.TestCurrentServer -> handleLayoutTestClick()
                    MainAction.ImportQRcode -> importQRcode()
                    MainAction.ImportClipboard -> importClipboard()
                    MainAction.ImportConfigLocal -> importConfigLocal()
                    is MainAction.ImportManually -> importManually(action.type)
                    MainAction.RestartService -> restartV2Ray()
                    MainAction.LocateSelectedServer -> mainViewModel.triggerLocateSelectedServer()
                    is MainAction.SelectServer -> setSelectServer(action.guid)
                    is MainAction.EditServer -> editServer(action.guid, action.profile)
                    is MainAction.ShareClipboard -> shareToClipboard(action.guid)
                    is MainAction.ShareFullContent -> shareFullContentAsync(action.guid)
                    else -> mainViewModel.onAction(action)
                }
            },
            onNavigate = { route -> navigateTo(route) },
            shareMethodEntries = resources.getStringArray(R.array.share_method).toList(),
            shareMethodMoreEntries = resources.getStringArray(R.array.share_method_more).toList()
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

    private fun navigateTo(destination: String) {
        val intent = when (destination) {
            "sub_setting" -> Intent(this, SubSettingActivity::class.java)
            "per_app_proxy" -> Intent(this, PerAppProxyActivity::class.java)
            "routing_setting" -> Intent(this, RoutingSettingActivity::class.java)
            "user_asset" -> Intent(this, UserAssetActivity::class.java)
            "settings" -> Intent(this, SettingsActivity::class.java)
            "logcat" -> Intent(this, LogcatActivity::class.java)
            "check_update" -> Intent(this, CheckUpdateActivity::class.java)
            "backup_restore" -> Intent(this, BackupActivity::class.java)
            "about" -> Intent(this, AboutActivity::class.java)
            "promotion" -> {
                requestPaymentUrl(1)
                return
            }
            else -> return
        }
        settingsActivityLauncher.launch(intent)
    }

    private fun handleFabAction() {
        if (mainViewModel.uiState.value.isRunning) {
            CoreServiceManager.stopVService(this)
        } else if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) startV2Ray() else requestVpnPermission.launch(intent)
        } else {
            startV2Ray()
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.uiState.value.isRunning) {
            mainViewModel.testCurrentServerRealPing()
        }
    }

    private fun startV2Ray() {
        if (mainViewModel.uiState.value.selectedGuid.isNullOrEmpty()) {
            autoAuthenticateDevice()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)
        ) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        CoreServiceManager.startVService(this)
    }

    private fun restartV2Ray() {
        if (mainViewModel.uiState.value.isRunning) CoreServiceManager.stopVService(this)
        lifecycleScope.launch {
            kotlinx.coroutines.delay(500)
            startV2Ray()
        }
    }

    private fun importManually(createConfigType: EConfigType) {
        val intent = when (createConfigType) {
            EConfigType.POLICYGROUP -> Intent(this, ServerGroupActivity::class.java)
            EConfigType.PROXYCHAIN -> Intent(this, ServerProxyChainActivity::class.java)
            EConfigType.VMESS -> Intent(this, ServerVmessActivity::class.java)
            EConfigType.VLESS -> Intent(this, ServerVlessActivity::class.java)
            EConfigType.SHADOWSOCKS -> Intent(this, ServerShadowsocksActivity::class.java)
            EConfigType.SOCKS -> Intent(this, ServerSocksActivity::class.java)
            EConfigType.HTTP -> Intent(this, ServerHttpActivity::class.java)
            EConfigType.TROJAN -> Intent(this, ServerTrojanActivity::class.java)
            EConfigType.WIREGUARD -> Intent(this, ServerWireguardActivity::class.java)
            EConfigType.HYSTERIA2 -> Intent(this, ServerHysteria2Activity::class.java)
            else -> Intent(this, ServerHttpActivity::class.java).apply {
                putExtra("createConfigType", createConfigType.value)
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
            val text = Utils.getClipboard(this).trim()
            if (text.isNotEmpty()) {
                mainViewModel.onAction(MainAction.ImportBatchConfig(text))
            } else {
                autoAuthenticateDevice()
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            autoAuthenticateDevice()
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
            EConfigType.CUSTOM -> ServerCustomConfigActivity::class.java
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
            mainViewModel.updateSelectedGuid(guid)
            if (mainViewModel.uiState.value.isRunning) restartV2Ray()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
