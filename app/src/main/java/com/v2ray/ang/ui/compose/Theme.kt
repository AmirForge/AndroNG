package com.v2ray.ang.ui.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val LightColor = lightColorScheme(
    primary = Color(0xFF8A365D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF4CFE0),
    onPrimaryContainer = Color(0xFF492038),
    secondary = Color(0xFF775164),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3DFEB),
    onSecondaryContainer = Color(0xFF442739),
    tertiary = Color(0xFF196347),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5F3E4),
    onTertiaryContainer = Color(0xFF123C2C),
    background = Color(0xFFF9F5F8),
    surface = Color(0xFFF9F5F8),
    onBackground = Color(0xFF232127),
    onSurface = Color(0xFF232127),
    surfaceVariant = Color(0xFFEAE5EA),
    onSurfaceVariant = Color(0xFF514A54),
    outline = Color(0xFF7B727C),
    outlineVariant = Color(0xFFD7D0D8),
    inverseSurface = Color(0xFF382A34),
    inverseOnSurface = Color(0xFFFDEEF6),
    inversePrimary = Color(0xFFF0A4C8),
    surfaceTint = Color(0xFF8A365D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F3F5),
    surfaceContainer = Color(0xFFF0ECEF),
    surfaceContainerHigh = Color(0xFFECE6EB),
    surfaceContainerHighest = Color(0xFFE6DFE5),
)

private val DarkColor = darkColorScheme(
    primary = Color(0xFFF09DC8),
    onPrimary = Color(0xFF3B142A),
    primaryContainer = Color(0xFF503045),
    onPrimaryContainer = Color(0xFFFFD6E9),
    secondary = Color(0xFFDEA9C4),
    onSecondary = Color(0xFF422639),
    secondaryContainer = Color(0xFF503548),
    onSecondaryContainer = Color(0xFFF9DCEF),
    tertiary = Color(0xFF95D5B9),
    onTertiary = Color(0xFF17372B),
    tertiaryContainer = Color(0xFF244B3B),
    onTertiaryContainer = Color(0xFFD5F3E4),
    background = Color(0xFF17151A),
    surface = Color(0xFF211C23),
    onBackground = Color(0xFFF1EDF0),
    onSurface = Color(0xFFF1EDF0),
    surfaceVariant = Color(0xFF42343F),
    onSurfaceVariant = Color(0xFFD4C7D1),
    outline = Color(0xFFA391A0),
    outlineVariant = Color(0xFF584451),
    inverseSurface = Color(0xFFF6EDF3),
    inverseOnSurface = Color(0xFF261C25),
    inversePrimary = Color(0xFF8A365D),
    surfaceTint = Color(0xFFF09DC8),
    surfaceContainerLowest = Color(0xFF131116),
    surfaceContainerLow = Color(0xFF241F27),
    surfaceContainer = Color(0xFF2B232D),
    surfaceContainerHigh = Color(0xFF342A35),
    surfaceContainerHighest = Color(0xFF44313F),
)

val colorConfigType = Color(0xFFAE5784)
val dividerColorLight = Color(0xFFDCD5DD)
val dividerColorDark = Color(0xFF494451)

// Toast Colors 85%
val toastNormalBgLight = Color(0xF23B2A38)
val toastNormalBgDark = Color(0xF2432A3C)
val toastSuccessBg = Color(0xD9388E3C) // Green
val toastErrorBg = Color(0xD9D50000) // Red
val toastInfoBg = Color(0xF25D3451)
val toastIconCircleBg = Color(0x33FFFFFF) // Semi-transparent White
val toastTextColor = Color.White // White

object ThemeManager {
    const val DEFAULT_THEME_MODE = "2"
    const val DEFAULT_DYNAMIC_COLOR = false

    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, DEFAULT_THEME_MODE) ?: DEFAULT_THEME_MODE
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, DEFAULT_DYNAMIC_COLOR)
    )
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, DEFAULT_THEME_MODE) ?: DEFAULT_THEME_MODE
        _dynamicColorEnabled.value =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, DEFAULT_DYNAMIC_COLOR)
    }
}

@Composable
fun resolveDarkTheme(): Boolean = true

val LocalDarkTheme = compositionLocalOf { true }

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val dynamicColor by ThemeManager.dynamicColorEnabled.collectAsState()
    val context = LocalContext.current
    val effectiveDarkTheme = true
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicDarkColorScheme(context)
        }

        effectiveDarkTheme -> DarkColor
        else -> LightColor
    }
    val snackbarController = rememberAppSnackbarController()
    val density = LocalDensity.current
    val locale = LocalConfiguration.current.locales[0]
    val textDensity = if (locale?.language == "fa") {
        Density(density.density, density.fontScale * 0.92f)
    } else {
        density
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides effectiveDarkTheme,
        LocalAppSnackbar provides snackbarController,
        LocalDensity provides textDensity,
    ) {
        MaterialTheme(
            colorScheme = colorScheme
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}
