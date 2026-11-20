package com.v2ray.ang.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.core.AetherExitNode
import com.v2ray.ang.ui.compose.FormDropdownField

/**
 * The Outrole profile chained after Aether so traffic exits through Aether into the chosen profile,
 * making that profile's IP the final exit IP. [value] is the profile name chosen, blank for none.
 */
@Composable
internal fun OutroleField(
    value: String,
    nodes: List<AetherExitNode>?,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
) {
    val none = stringResource(R.string.aether_outrole_none)
    val numbered = stringResource(R.string.label_numbered)
    val names = nodes.orEmpty().map { it.name }
    val labels = remember(names, none, numbered) {
        distinctLabels(names, setOf(none)) { name, number -> numbered.format(name, number) }
    }
    val name = value.trim()
    val problem = problemOfExitNode(name, nodes)
    FormDropdownField(
        label = stringResource(R.string.aether_lab_outrole),
        value = if (name.isEmpty()) none else labels.getOrNull(names.indexOf(name)) ?: name,
        options = listOf(none) + labels,
        onValueChange = { picked ->
            if (picked == none) {
                onValueChange("")
            } else {
                labels.indexOf(picked).takeIf { it >= 0 }?.let { onValueChange(names[it]) }
            }
        },
        modifier = Modifier.testTag("aether_outrole_field"),
        enabled = enabled,
        supportingText = when {
            name.isEmpty() -> null
            problem != null -> stringResource(problem.message, name)
            else -> stringResource(R.string.aether_hint_outrole)
        },
    )
}
