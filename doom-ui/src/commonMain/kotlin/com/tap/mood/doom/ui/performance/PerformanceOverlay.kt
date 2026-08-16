@file:Suppress("FunctionName")

package com.tap.mood.doom.ui.performance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tap.mood.doom.ui.resources.Res
import com.tap.mood.doom.ui.resources.doom_fps
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
fun PerformanceOverlay(
    framesPerSecond: Double,
    modifier: Modifier = Modifier,
) {
    Text(
        text =
            stringResource(
                Res.string.doom_fps,
                ((framesPerSecond * 10).roundToInt() / 10.0).toString(),
            ),
        color = Color.White.copy(alpha = 0.8f),
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
