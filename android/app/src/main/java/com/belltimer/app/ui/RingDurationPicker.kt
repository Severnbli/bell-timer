package com.belltimer.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.belltimer.app.R
import com.belltimer.app.data.MAX_RING_SECONDS
import com.belltimer.app.data.MIN_RING_SECONDS
import com.belltimer.app.data.QUICK_RING_SECONDS_MAX

/**
 * Compact bell duration selector: chips for the common 1..10 s range plus a
 * "Другое" chip that reveals a free-form field (1..60 s).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RingDurationPicker(
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    var customMode by rememberSaveable { mutableStateOf(seconds > QUICK_RING_SECONDS_MAX) }
    var customText by rememberSaveable { mutableStateOf(seconds.toString()) }

    // Keep the free-form text in sync when the value is changed from outside.
    LaunchedEffect(seconds) {
        if (seconds.toString() != customText) customText = seconds.toString()
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (value in MIN_RING_SECONDS..QUICK_RING_SECONDS_MAX) {
                FilterChip(
                    selected = !customMode && seconds == value,
                    onClick = {
                        customMode = false
                        onSecondsChange(value)
                    },
                    label = { Text(value.toString()) },
                )
            }
            FilterChip(
                selected = customMode,
                onClick = { customMode = true },
                label = { Text(stringResource(R.string.duration_other)) },
                leadingIcon = if (customMode) {
                    {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }

        AnimatedVisibility(visible = customMode) {
            val parsed = customText.toIntOrNull()
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
                supportingText = {
                    Text(
                        stringResource(
                            R.string.duration_range_hint,
                            MIN_RING_SECONDS,
                            MAX_RING_SECONDS,
                        ),
                    )
                },
                isError = customText.isNotEmpty() &&
                    (parsed == null || parsed !in MIN_RING_SECONDS..MAX_RING_SECONDS),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
