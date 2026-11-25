package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

@Composable
internal fun DiagnosticOutRuleDialog(state: MainUiState, onAction: (MainAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(MainAction.DismissDiagnosticConfigs) },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.aether_build_choose_out_rule), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    state.diagnosticLoading -> CircularProgressIndicator()
                    state.diagnosticOutRules.isEmpty() -> Text(stringResource(R.string.aether_build_no_out_rules))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.diagnosticOutRules, key = { it.guid }) { choice ->
                            ConfigChoiceCard(
                                onClick = { onAction(MainAction.BuildDiagnosticConfigsWithOutRule(choice.guid)) },
                                title = choice.remarks,
                                icon = R.drawable.ic_build_configs,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { onAction(MainAction.DismissDiagnosticConfigs) }) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
