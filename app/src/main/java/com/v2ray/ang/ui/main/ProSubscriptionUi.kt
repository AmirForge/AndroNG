package com.v2ray.ang.ui.main

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import kotlinx.coroutines.delay

@Composable
internal fun ProBadge() {
    val transition = rememberInfiniteTransition(label = "Pro glow")
    val glow by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "Gentle lettering brightness")
    val color = MaterialTheme.colorScheme.primary
    val glowRadius = with(LocalDensity.current) { (4f + 18f * glow).dp.toPx() }
    val raceFont = remember {
        // The supplied face is already italic; synthesize only its requested bold weight.
        FontFamily(Font(R.font.greats_race_italic, weight = FontWeight.Normal,
            style = FontStyle.Italic, loadingStrategy = FontLoadingStrategy.Async))
    }
    Box(Modifier.width(54.dp).height(44.dp).absoluteOffset(x = (-4).dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = (18f + 12f * glow).dp.toPx()
            scale(scaleX = 1.25f, scaleY = .72f, pivot = center) {
                drawCircle(
                    brush = Brush.radialGradient(listOf(color.copy(alpha = .035f + .24f * glow),
                        color.copy(alpha = .015f + .10f * glow), Color.Transparent), center, radius),
                    radius = radius, center = center,
                )
            }
        }
        Text(
            text = stringResource(R.string.pro_badge), color = lerp(color, Color.White, .55f * glow),
            fontFamily = raceFont, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
            fontSize = 26.sp, lineHeight = 30.sp, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Visible,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSynthesis = FontSynthesis.Weight, textDirection = TextDirection.Ltr,
                shadow = Shadow(color.copy(alpha = .18f + .72f * glow), Offset.Zero, blurRadius = glowRadius),
            ),
        )
    }
}

@Composable
internal fun ProCodeDialog(state: MainUiState, onAction: (MainAction) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.proBlockedUntil) {
        while (now < state.proBlockedUntil) { delay(1000); now = System.currentTimeMillis() }
    }
    val blocked = state.proBlockedUntil > now
    val canSubmit = code.isNotBlank() && !state.proBusy && !blocked
    val submit = { if (canSubmit) onAction(MainAction.SubmitProCode(code)) }
    val buyTransition = rememberInfiniteTransition(label = "Pro purchase glow")
    val buyGlow by buyTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2200), repeatMode = RepeatMode.Reverse), label = "Purchase halo")
    val purchaseColor = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = { if (!state.proBusy) onAction(MainAction.DismissProSubscription) },
        title = { Text(stringResource(R.string.pro_subscription)) },
        text = {
            Column(Modifier.imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.pro_code_hint))
                Button(
                    onClick = { onAction(MainAction.BuyProCode) }, enabled = !state.proBusy,
                    modifier = Modifier.fillMaxWidth().shadow((4f + 6f * buyGlow).dp, RoundedCornerShape(14.dp),
                        ambientColor = purchaseColor.copy(alpha = .25f), spotColor = purchaseColor.copy(alpha = .65f)),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(R.string.pro_buy_code)) }
                OutlinedTextField(value = code, onValueChange = { code = it.take(128) }, singleLine = true,
                    label = { Text(stringResource(R.string.pro_code_label)) }, enabled = !state.proBusy && !blocked,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }))
                if (blocked) Text(stringResource(R.string.pro_cooldown, ((state.proBlockedUntil - now + 59999) / 60000).toInt()), color = MaterialTheme.colorScheme.error)
                else state.proError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = submit, enabled = canSubmit) { Text(stringResource(R.string.pro_activate)) } },
        dismissButton = { TextButton(onClick = { onAction(MainAction.DismissProSubscription) }, enabled = !state.proBusy) { Text(stringResource(android.R.string.cancel)) } },
    )
}
