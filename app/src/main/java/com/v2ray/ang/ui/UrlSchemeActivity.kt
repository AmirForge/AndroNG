package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.fmt.AetherSetupLink
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import java.net.URLDecoder

class UrlSchemeActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            var setupImport: Intent? = null
            intent.apply {
                if (action == Intent.ACTION_SEND) {
                    if ("text/plain" == type) {
                        intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                            setupImport = parseUri(it, null)
                        }
                    }
                } else if (action == Intent.ACTION_VIEW) {
                    if (data?.scheme == "andro") {
                        setupImport = parseUri(dataString, null)
                    } else if (data?.scheme == "v2rayng") when (data?.host) {
                        "install-config" -> {
                            val uri: Uri? = intent.data
                            val shareUrl = uri?.getQueryParameter("url").orEmpty()
                            setupImport = parseUri(shareUrl, uri?.fragment)
                        }

                        "install-sub" -> {
                            val uri: Uri? = intent.data
                            val shareUrl = uri?.getQueryParameter("url").orEmpty()
                            setupImport = parseUri(shareUrl, uri?.fragment)
                        }

                        else -> {
                            toastError(R.string.toast_failure)
                        }
                    }
                }
            }

            startActivity(setupImport ?: Intent(this, MainActivity::class.java))
            finish()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error processing URL scheme", HttpUtil.privateDownloadError(e))
        }
    }

    @Composable
    override fun ScreenContent() {
    }

    private fun parseUri(uriString: String?, fragment: String?): Intent? {
        if (uriString.isNullOrBlank() || uriString.length > MAX_EXTERNAL_IMPORT_CHARS) return null
        // The exported entry point only prepares a request. Main owns confirmation and all writes.
        if (AetherSetupLink.containsSetupLinks(uriString)) return MainActivity.aetherImportIntent(this, uriString)
        var decodedUrl = URLDecoder.decode(uriString, "UTF-8")
        if (Uri.parse(decodedUrl).fragment.isNullOrEmpty() && !fragment.isNullOrEmpty()) decodedUrl += "#$fragment"
        if (decodedUrl.length > MAX_EXTERNAL_IMPORT_CHARS) return null
        return MainActivity.aetherImportIntent(this, decodedUrl)
    }

    companion object {
        internal const val MAX_EXTERNAL_IMPORT_CHARS = 512 * 1024
    }
}
