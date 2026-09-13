package com.dopachiru.focus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.model.Focus
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.ui.theme.DopaTheme

/**
 * ホーム画面から、長さを選んで止めるための入口。
 *
 * [FocusShortcutActivity] は長さが決め打ちの1タップ。こちらは
 * **その場で何分かを選ぶ**もので、「あと25分だけ」のような
 * 予定に合わせた止め方をショートカット1つでまかなえる。
 *
 * 選択肢は5分刻みで30通り(5〜150分)を5列に並べる。数字を打ち込ませない
 * のは、キーボードが出た時点で「思い立った瞬間に始める」が壊れるため。
 *
 * すでに走っているときは、始め直さずに残りと足す手段だけを見せる。
 * 押し間違いで長さが化けるのがいちばん困る。
 */
class FocusPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DopaRuntime.init(this)

        val running = DopaRuntime.activeFocus()
        val nowSec = System.currentTimeMillis() / 1000

        // 型が指しているなら、その範囲で止める。無ければ「逃がすもの以外ぜんぶ」。
        val template = intent?.getStringExtra(EXTRA_TEMPLATE_ID)?.let { DopaRuntime.focusTemplate(it) }
        val heading = template?.displayLabel()

        setContent {
            DopaTheme {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            if (running != null) {
                                RunningPanel(
                                    remainingMinutes = running.remainingMinutesAt(nowSec),
                                    onExtend = { minutes ->
                                        DopaRuntime.extendFocus(minutes)
                                        done("あと${minutes}分足しました")
                                    },
                                    onClose = { finishQuietly() },
                                )
                            } else {
                                PickPanel(
                                    heading = heading,
                                    onPick = { minutes ->
                                        val started = if (template != null) {
                                            DopaRuntime.startFocus(template, minutes)
                                        } else {
                                            DopaRuntime.startFocus(minutes)
                                        }
                                        if (started) {
                                            done("${Focus.clampMinutes(minutes)}分止めます")
                                        } else {
                                            done("始められませんでした")
                                        }
                                    },
                                    onClose = { finishQuietly() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun done(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finishQuietly()
    }

    private fun finishQuietly() {
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_TEMPLATE_ID = "template"

        fun intent(context: Context, templateId: String? = null): Intent =
            Intent(context, FocusPickerActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                if (templateId != null) putExtra(EXTRA_TEMPLATE_ID, templateId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
    }
}

@Composable
private fun PickPanel(heading: String?, onPick: (Int) -> Unit, onClose: () -> Unit) {
    Text(
        if (heading != null) "$heading・何分?" else "何分止める?",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        if (heading != null) {
            "選んだ時間だけ、この範囲が開かなくなります。"
        } else {
            "選んだ時間だけ、逃がすもの以外が開かなくなります。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    LazyVerticalGrid(
        columns = GridCells.Fixed(Focus.PICK_COLUMNS),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().height(320.dp),
    ) {
        items(Focus.PICK_CHOICES) { minutes ->
            FilledTonalButton(
                onClick = { onPick(minutes) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                modifier = Modifier.height(48.dp),
            ) {
                Text("$minutes", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onClose) { Text("やめる") }
    }
}

@Composable
private fun RunningPanel(
    remainingMinutes: Int,
    onExtend: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Text("集中中", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text("あと${remainingMinutes}分", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "切り上げるには、止まっている画面から。ここからは足すだけです。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Focus.EXTEND_CHOICES.forEach { minutes ->
            OutlinedButton(onClick = { onExtend(minutes) }) { Text("+$minutes") }
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onClose) { Text("閉じる") }
    }
    Spacer(Modifier.width(0.dp))
}
