package com.joe6355.astrophoto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.joe6355.astrophoto.ui.AstroSlider

@Composable
internal fun ExposureAdjustment(value: Long, range: LongRange, onValueChange: (Long) -> Unit,
    enabled: Boolean = true, modifier: Modifier = Modifier) {
    val second = 1_000_000_000L
    val hasShort = range.first < second
    val hasLong = range.last >= second
    var seconds by remember(range, value >= second) { mutableStateOf(value >= second || !hasShort) }
    val selectedRange = if (seconds && hasLong) maxOf(range.first, second)..range.last
        else range.first..minOf(range.last, second)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(formatExposure(value), style = MaterialTheme.typography.titleMedium)
        if (hasShort && hasLong) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !seconds, onClick = { seconds = false }, enabled = enabled,
                    label = { Text("Доли секунды") })
                FilterChip(selected = seconds, onClick = { seconds = true }, enabled = enabled,
                    label = { Text("Секунды") })
            }
        }
        AstroSlider(value = exposureSliderFraction(value, selectedRange),
            onValueChange = { onValueChange(exposureFromSliderFraction(it, selectedRange)) },
            enabled = enabled && selectedRange.first < selectedRange.last,
            modifier = Modifier.fillMaxWidth().testTag("exposure-adjustment-slider"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatExposure(selectedRange.first), style = MaterialTheme.typography.bodySmall)
            Text(formatExposure(selectedRange.last), style = MaterialTheme.typography.bodySmall)
        }
    }
}
