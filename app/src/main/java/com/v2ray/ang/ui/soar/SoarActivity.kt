package com.v2ray.ang.ui.soar

import android.net.VpnService
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.ui.base.BaseComponentActivity

class SoarActivity : BaseComponentActivity() {
    private val model: SoarViewModel by viewModels()
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.act(SoarAction.Permission(it.resultCode == RESULT_OK))
    }
    private val file = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.act(SoarAction.ReadFile(it)) }
    }

    @Composable
    override fun ScreenContent() {
        val state by model.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.permissionRequest) {
            if (state.permissionRequest > 0) {
                val intent = VpnService.prepare(this@SoarActivity)
                if (intent == null) model.act(SoarAction.Permission(true)) else permission.launch(intent)
            }
        }
        SoarScreen(state, model::act, { finish() }, { file.launch(arrayOf("text/*", "application/octet-stream")) })
    }

    override fun onResume() { super.onResume(); model.act(SoarAction.Refresh) }
}
