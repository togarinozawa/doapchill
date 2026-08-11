package com.dopachiru.ui.minigame

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import kotlin.random.Random

private data class Problem(val a: Int, val b: Int, val c: Int) {
    val text: String get() = "$a × $b + $c"
    val answer: Int get() = a * b + c
}

private fun newProblem(): Problem =
    Problem(Random.nextInt(11, 30), Random.nextInt(3, 13), Random.nextInt(10, 99))

/**
 * ゲート用のミニゲーム。
 *
 * 難しさより「面倒くささ」を狙っている。暗算でも解けるが、
 * 衝動的に設定を緩めようとした瞬間には十分な障壁になる。
 * 途中で間違えると最初からやり直し。
 */
@Composable
fun ArithmeticGame(
    rounds: Int,
    onCleared: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var cleared by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf(newProblem()) }
    var input by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(
            progress = { cleared.toFloat() / rounds.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "$cleared / $rounds",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        Text(
            problem.text,
            fontSize = 32.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = input,
            onValueChange = { value ->
                input = value.filter { it.isDigit() }
                wrong = false
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = wrong,
            supportingText = if (wrong) {
                { Text("違います。最初からやり直しです。") }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val value = input.toIntOrNull()
                if (value == problem.answer) {
                    val next = cleared + 1
                    input = ""
                    if (next >= rounds) {
                        onCleared()
                    } else {
                        cleared = next
                        problem = newProblem()
                    }
                } else {
                    wrong = true
                    cleared = 0
                    input = ""
                    problem = newProblem()
                }
            },
            enabled = input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("答える")
        }
    }
}
