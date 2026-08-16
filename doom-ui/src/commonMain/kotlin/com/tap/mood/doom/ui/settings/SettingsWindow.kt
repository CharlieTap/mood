@file:Suppress("FunctionName")

package com.tap.mood.doom.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tap.mood.doom.runtime.settings.Border
import com.tap.mood.doom.runtime.settings.Detail
import com.tap.mood.doom.ui.resources.Res
import com.tap.mood.doom.ui.resources.graphics_aspect_corrected
import com.tap.mood.doom.ui.resources.graphics_aspect_fill
import com.tap.mood.doom.ui.resources.graphics_aspect_raw
import com.tap.mood.doom.ui.resources.graphics_classic
import com.tap.mood.doom.ui.resources.graphics_smooth
import com.tap.mood.doom.ui.resources.settings_aspect_ratio
import com.tap.mood.doom.ui.resources.settings_display
import com.tap.mood.doom.ui.resources.settings_done
import com.tap.mood.doom.ui.resources.settings_gamma
import com.tap.mood.doom.ui.resources.settings_graphic_detail
import com.tap.mood.doom.ui.resources.settings_high
import com.tap.mood.doom.ui.resources.settings_large
import com.tap.mood.doom.ui.resources.settings_low
import com.tap.mood.doom.ui.resources.settings_medium
import com.tap.mood.doom.ui.resources.settings_performance
import com.tap.mood.doom.ui.resources.settings_scaling
import com.tap.mood.doom.ui.resources.settings_small
import com.tap.mood.doom.ui.resources.settings_title
import com.tap.mood.doom.ui.resources.settings_view_size
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
fun SettingsWindow(
    settings: Settings,
    onSettingsChanged: ((Settings) -> Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_title)) },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.widthIn(max = 720.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.settings_display))
                    ChoiceRow(
                        label = stringResource(Res.string.settings_aspect_ratio),
                        choices =
                            listOf(
                                stringResource(Res.string.graphics_aspect_corrected),
                                stringResource(Res.string.graphics_aspect_raw),
                                stringResource(Res.string.graphics_aspect_fill),
                            ),
                        selectedIndex = settings.display.aspectRatio.ordinal,
                        onSelected = { index ->
                            onSettingsChanged { current ->
                                current.copy(
                                    display = current.display.copy(aspectRatio = AspectRatio.entries[index]),
                                )
                            }
                        },
                    )
                    ChoiceRow(
                        label = stringResource(Res.string.settings_scaling),
                        choices =
                            listOf(
                                stringResource(Res.string.graphics_classic),
                                stringResource(Res.string.graphics_smooth),
                            ),
                        selectedIndex = settings.display.scaling.ordinal,
                        onSelected = { index ->
                            onSettingsChanged { current ->
                                current.copy(display = current.display.copy(scaling = Scaling.entries[index]))
                            }
                        },
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.settings_performance))
                    ChoiceRow(
                        label = stringResource(Res.string.settings_graphic_detail),
                        choices =
                            listOf(
                                stringResource(Res.string.settings_high),
                                stringResource(Res.string.settings_low),
                            ),
                        selectedIndex = settings.engine.detail.ordinal,
                        onSelected = { index ->
                            onSettingsChanged { current ->
                                current.copy(
                                    engine = current.engine.copy(detail = Detail.entries[index]),
                                )
                            }
                        },
                    )
                    ChoiceRow(
                        label = stringResource(Res.string.settings_view_size),
                        choices =
                            listOf(
                                stringResource(Res.string.settings_large),
                                stringResource(Res.string.settings_medium),
                                stringResource(Res.string.settings_small),
                            ),
                        selectedIndex = settings.engine.border.ordinal,
                        onSelected = { index ->
                            onSettingsChanged { current ->
                                current.copy(engine = current.engine.copy(border = Border.entries[index]))
                            }
                        },
                    )
                    Text(
                        text = stringResource(Res.string.settings_gamma, settings.engine.gammaLevel),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Slider(
                        value = settings.engine.gammaLevel.toFloat(),
                        onValueChange = { value ->
                            onSettingsChanged { current ->
                                current.copy(engine = current.engine.copy(gammaLevel = value.roundToInt()))
                            }
                        },
                        valueRange = 0f..4f,
                        steps = 3,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.settings_done))
            }
        },
    )
}

@Composable
private fun ChoiceRow(
    label: String,
    choices: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    Text(label, modifier = Modifier.padding(top = 6.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        choices.forEachIndexed { index, choice ->
            val selected = index == selectedIndex
            Surface(
                color = if (selected) MaterialTheme.colors.primary else Color.Transparent,
                contentColor = if (selected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.4f)),
                shape = MaterialTheme.shapes.small,
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .selectable(selected = selected, onClick = { onSelected(index) }),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 8.dp),
                ) {
                    Text(text = choice, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}
