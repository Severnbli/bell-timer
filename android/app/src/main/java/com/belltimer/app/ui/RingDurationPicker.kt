package com.belltimer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.belltimer.app.R
import com.belltimer.app.data.MAX_RING_SECONDS
import com.belltimer.app.data.MIN_RING_SECONDS
import com.belltimer.app.data.SLIDER_MAX_SECONDS
import kotlin.math.roundToInt

/**
 * Bell duration: a 1..10 s slider plus an opt-in free field for 1..60 s.
 * The same control is used on the control screen, in settings and in every
 * bell editor.
 */
@Composable
fun RingDurationPicker(
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    var customMode by rememberSaveable { mutableStateOf(seconds > SLIDER_MAX_SECONDS) }
    var customText by rememberSaveable { mutableStateOf(seconds.toString()) }

    // Keep the field in sync when the value changes from outside.
    LaunchedEffect(seconds) {
        if (seconds in MIN_RING_SECONDS..MAX_RING_SECONDS && customText != seconds.toString()) {
            customText = seconds.toString()
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!customMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = seconds.coerceIn(MIN_RING_SECONDS, SLIDER_MAX_SECONDS).toFloat(),
                    onValueChange = { value ->
                        onSecondsChange(
                            value.roundToInt().coerceIn(MIN_RING_SECONDS, SLIDER_MAX_SECONDS),
                        )
                    },
                    valueRange = MIN_RING_SECONDS.toFloat()..SLIDER_MAX_SECONDS.toFloat(),
                    steps = SLIDER_MAX_SECONDS - MIN_RING_SECONDS - 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(
                        R.string.duration_seconds,
                        seconds.coerceIn(MIN_RING_SECONDS, SLIDER_MAX_SECONDS),
                    ),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFeatureSettings = TabularNumbers.fontFeatureSettings,
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TextButton(
                onClick = {
                    customText = seconds.toString()
                    customMode = true
                },
            ) {
                Text(stringResource(R.string.duration_custom))
            }
        } else {
            val parsed = customText.toIntOrNull()
            val valid = parsed != null && parsed in MIN_RING_SECONDS..MAX_RING_SECONDS
            OutlinedTextField(
                value = customText,
                onValueChange = { value ->
                    if (value.length <= 2 && value.all { it.isDigit() }) {
                        customText = value
                        value.toIntOrNull()
                            ?.takeIf { it in MIN_RING_SECONDS..MAX_RING_SECONDS }
                            ?.let(onSecondsChange)
                    }
                },
                label = { Text(stringResource(R.string.duration_custom_label)) },
                singleLine = true,
                isError = customText.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            )
            if (customText.isNotEmpty() && !valid) {
                Text(
                    text = stringResource(
                        R.string.duration_range_hint,
                        MIN_RING_SECONDS,
                        MAX_RING_SECONDS,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(
                onClick = {
                    customMode = false
                    // The slider cannot show a larger value, so it takes the
                    // largest one it can represent.
                    onSecondsChange(seconds.coerceIn(MIN_RING_SECONDS, SLIDER_MAX_SECONDS))
                },
            ) {
                Text(stringResource(R.string.duration_back_to_slider))
            }
        }
    }
}
