package com.v2ray.ang.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.AppDivider
import com.v2ray.ang.ui.compose.verticalScrollbar

enum class MainDestination(@DrawableRes val iconRes: Int, @StringRes val labelRes: Int) {
    Subscriptions(R.drawable.ic_subscriptions_24dp, R.string.title_sub_setting),
    PerAppProxy(R.drawable.ic_per_apps_24dp, R.string.per_app_proxy_settings),
    Routing(R.drawable.ic_routing_24dp, R.string.routing_settings_title),
    UserAssets(R.drawable.ic_file_24dp, R.string.title_user_asset_setting),
    Settings(R.drawable.ic_settings_24dp, R.string.title_settings),
    Logcat(R.drawable.ic_logcat_24dp, R.string.title_logcat),
    BackupRestore(R.drawable.ic_restore_24dp, R.string.title_configuration_backup_restore),
    About(R.drawable.ic_about_24dp, R.string.title_about)
}

private val primaryDrawerItems = listOf(
    MainDestination.Subscriptions,
    MainDestination.PerAppProxy,
    MainDestination.Routing,
    MainDestination.UserAssets,
    MainDestination.Settings
)

private val drawerItems = primaryDrawerItems + listOf(
    MainDestination.Logcat,
    MainDestination.BackupRestore,
    MainDestination.About
)

@Composable
fun MainDrawerContent(drawerState: DrawerState, onNavigate: (MainDestination) -> Unit, onAction: (MainAction) -> Unit) {
    val drawerScrollState = rememberScrollState()
    var contactOpen by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    ModalDrawerSheet(
        drawerState = drawerState,
        modifier = Modifier.fillMaxWidth(0.75f),
        drawerContainerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(drawerScrollState)
                .verticalScrollbar(drawerScrollState)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // v2meda: full-color circular logo, no dark-mode tint (tinting a photo logo
                    // would flatten it into a white disc)
                    Image(
                        painter = painterResource(R.drawable.ic_v2meda_logo),
                        contentDescription = null,
                        modifier = Modifier.size(120.dp)
                    )
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                onClick = { contactOpen = true },
                shape = MaterialTheme.shapes.large,
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
                border = BorderStroke(0.75.dp, colors.primary.copy(alpha = 0.72f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_telegram_24dp), contentDescription = null)
                    Text(stringResource(R.string.contact_us), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            Surface(
                onClick = { onAction(MainAction.OpenProSubscription) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                shape = MaterialTheme.shapes.large,
                color = colors.primary,
                contentColor = colors.onPrimary,
                border = BorderStroke(1.dp, colors.primary),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.ic_real_delay_bolt), contentDescription = null, tint = colors.onPrimary)
                    Text(stringResource(R.string.pro_subscription), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            drawerItems.forEachIndexed { index, item ->
                if (index == primaryDrawerItems.size) AppDivider()
                NavigationDrawerItem(
                    label = { Text(stringResource(item.labelRes)) },
                    selected = false,
                    onClick = { onNavigate(item) },
                    icon = { Icon(painterResource(item.iconRes), contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    }
    if (contactOpen) {
        ContactDialog(onDismiss = { contactOpen = false }, onAction = onAction)
    }
}

@Composable
private fun ContactDialog(onDismiss: () -> Unit, onAction: (MainAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 400.dp)
                .fillMaxWidth().heightIn(max = maxHeight),
            shape = RoundedCornerShape(28.dp),
            color = colors.surfaceContainer,
            contentColor = colors.onSurface,
            border = BorderStroke(0.75.dp, colors.primary.copy(alpha = 0.42f)),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_v2meda_logo),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)),
                    )
                    Text(
                        text = stringResource(R.string.contact_us),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ContactCard(
                        label = R.string.contact_channel_label,
                        handle = R.string.contact_channel_handle,
                        onClick = {
                            onDismiss()
                            onAction(MainAction.OpenContact("v2meda"))
                        },
                    )
                    ContactCard(
                        label = R.string.contact_admin_label,
                        handle = R.string.contact_admin_handle,
                        onClick = {
                            onDismiss()
                            onAction(MainAction.OpenContact("v2meda_admin"))
                        },
                    )
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(0.75.dp, colors.primary.copy(alpha = 0.30f)),
                ) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}

@Composable
private fun ContactCard(@StringRes label: Int, @StringRes handle: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.contact_open_link, stringResource(label), stringResource(handle))
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainerHigh,
        border = BorderStroke(0.75.dp, colors.primary.copy(alpha = 0.30f)),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.primary.copy(alpha = 0.10f),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_telegram_24dp),
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.padding(11.dp).size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = stringResource(handle),
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
