package com.dopachiru.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dopachiru.core.model.OveruseReaction
import com.dopachiru.core.model.OveruseSettings
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.service.ScreenMarkers
import com.dopachiru.service.ShortsGuardSettings
import com.dopachiru.service.ShortsTarget
import com.dopachiru.ui.rules.AppPickerDialog
import com.dopachiru.ui.rules.InstalledApps
import kotlinx.coroutines.delay

/**
 * ショートを戻す。ルールを作らずに、入れた相手だけ常に効く。
 *
 * 目印(resource-id)は推測で入れてあるので、外れていたらこの画面で直せるようにする。
 * adb をつながなくても、本人が実際の画面で見えた id を選ぶだけで足りる。
 */
@Composable
internal fun ShortsGuardSection() {
    var guard by remember { mutableStateOf(DopaRuntime.shortsGuard) }
    fun update(next: ShortsGuardSettings) {
        guard = next
        DopaRuntime.setShortsGuard(next)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "入れた相手のショートを開くと、すぐ一つ前の画面へ戻します。" +
                    "アプリのほかの画面はそのまま使えます。TikTok はアプリごとホームへ戻します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ShortsTarget.entries.forEach { target ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(target.label, style = MaterialTheme.typography.bodyLarge)
                        val noMarker = !target.wholeApp &&
                            target.packages.none { ScreenMarkers.hasMarkers(it, guard) }
                        if (noMarker) {
                            Text(
                                "目印がまだありません。下の「目印を調べる」で足すと効きます",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Switch(
                        checked = guard.isOn(target),
                        onCheckedChange = { on ->
                            update(guard.copy(targets = if (on) guard.targets + target.id else guard.targets - target.id))
                        },
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    MarkerCaptureCard(guard = guard, onChange = ::update)
}

/**
 * 画面の目印を調べる。
 *
 * 押してから数十秒のあいだ、見えた画面の resource-id を拾っておく。そのあいだに
 * 相手のアプリでショートを開いてもらい、戻ってきて、それらしい id を目印に足す。
 * ショートを開いた**ときにだけ**見える id を選ぶのがこつ(ホームにも出る id だと、
 * ホームでも戻ってしまう)。
 */
@Composable
private fun MarkerCaptureCard(guard: ShortsGuardSettings, onChange: (ShortsGuardSettings) -> Unit) {
    val context = LocalContext.current
    val capture by DopaRuntime.markerCapture.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(capture?.untilMs) {
        while (capture?.running == true) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("目印を調べる", style = MaterialTheme.typography.titleMedium)
            Text(
                "押してから${CAPTURE_SECONDS}秒のあいだ、開いた画面の目印を記録します。" +
                    "そのあいだにショート(リール)を開いて、ここへ戻ってきてください。" +
                    "名前に reel・shorts・clips・video などが入っているものが目印の候補です。" +
                    "ホームにも出ている目印を選ぶと、ホームでも戻ってしまいます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val running = capture?.running == true
            OutlinedButton(onClick = { DopaRuntime.startMarkerCapture(CAPTURE_SECONDS) }, enabled = !running) {
                Text(
                    if (running) {
                        "記録中… あと${(((capture?.untilMs ?: now) - now) / 1000).coerceAtLeast(0)}秒"
                    } else {
                        "記録を始める"
                    },
                )
            }

            // 自分で足した目印。外せるように並べておく
            val added = guard.markers.flatMap { (pkg, ids) -> ids.map { pkg to it } }
            if (added.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("足した目印", style = MaterialTheme.typography.bodyLarge)
                added.forEach { (pkg, id) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${InstalledApps.labelOf(context, pkg)}: ${shortId(id)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onChange(guard.withoutMarker(pkg, id)) }) { Text("外す") }
                    }
                }
            }

            val seen = capture?.seen.orEmpty().filterKeys { ShortsTarget.forPackage(it) != null }
            if (seen.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                seen.forEach { (pkg, ids) ->
                    Spacer(Modifier.height(8.dp))
                    Text(InstalledApps.labelOf(context, pkg), style = MaterialTheme.typography.bodyLarge)
                    val builtIn = ScreenMarkers.builtInFor(pkg)
                    // 候補になりやすいものを上に。全部並べると数百になる
                    ids.sortedWith(compareByDescending<String> { likelyShort(it) }.thenBy { it })
                        .forEach { id ->
                            val already = id in builtIn || id in guard.markers[pkg].orEmpty()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    shortId(id),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (likelyShort(id)) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { onChange(guard.withMarker(pkg, id)) },
                                    enabled = !already,
                                ) { Text(if (already) "目印" else "目印にする") }
                            }
                        }
                }
            } else if (capture != null && !running) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "対象のアプリの画面は記録されませんでした。記録中に開けていたか確かめてください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val CAPTURE_SECONDS = 30

private fun shortId(id: String): String = id.substringAfter(":id/", id)

private fun likelyShort(id: String): Boolean {
    val tail = shortId(id).lowercase()
    return listOf("reel", "short", "clip", "video", "immersive").any { it in tail }
}

/**
 * ルールを作らなくても効く、使い過ぎの見張り。
 *
 * 決めることは「いつもの何倍で」「何をするか」の2つが中心。あとは既定で困らない値。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OveruseSection() {
    val context = LocalContext.current
    var s by remember { mutableStateOf(DopaRuntime.overuseSettings) }
    var picking by remember { mutableStateOf(false) }
    fun update(next: OveruseSettings) {
        s = next
        DopaRuntime.setOveruseSettings(next)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("見張る", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = s.enabled, onCheckedChange = { update(s.copy(enabled = it)) })
            }
            Text(
                "ルールを作っていないアプリも含めて、続けて使っている長さを、" +
                    "そのアプリのいつもの長さ(過去4週間の平均)と比べます。1分未満の使用は平均に入れません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!s.enabled) return@Column

            Spacer(Modifier.height(12.dp))
            NumberRow("いつもの", s.factorPercent, "%を超えたら", step = 25, min = 125, max = 500) {
                update(s.copy(factorPercent = it))
            }
            NumberRow("ただし", s.floorMinutes, "分までは言わない", step = 5, min = 5, max = 120) {
                update(s.copy(floorMinutes = it))
            }
            NumberRow("履歴が少ないアプリは", s.newAppMinutes, "分で", step = 5, min = 0, max = 180) {
                update(s.copy(newAppMinutes = it))
            }
            Text(
                if (s.newAppMinutes == 0) "履歴がたまるまで見張りません" else "平均を出すには、1分以上の使用が5回要ります",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NumberRow("その後も", s.repeatMinutes, "分ごとに", step = 5, min = 0, max = 120) {
                update(s.copy(repeatMinutes = it))
            }
            if (s.repeatMinutes == 0) {
                Text(
                    "一続きにつき1回だけ知らせます",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("知らせかた(いくつでも)", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OveruseReaction.entries.forEach { r ->
                    FilterChip(
                        selected = r in s.reactions,
                        onClick = {
                            update(s.copy(reactions = if (r in s.reactions) s.reactions - r else s.reactions + r))
                        },
                        label = { Text(r.label) },
                    )
                }
            }
            if (s.reactions.isEmpty()) {
                Text(
                    "何も選んでいないので、見張っても何も起きません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (OveruseReaction.INTERRUPT in s.reactions) {
                NumberRow("割り込んだら", s.interruptSeconds, "秒は続けられない", step = 5, min = 0, max = 60) {
                    update(s.copy(interruptSeconds = it))
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("見張らないアプリ", style = MaterialTheme.typography.bodyLarge)
            Text(
                "地図・音楽・通話のように、長く開いていて当然のもの。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            s.excludePackages.forEach { pkg ->
                Text("・${InstalledApps.labelOf(context, pkg)}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { picking = true }) { Text("選ぶ") }
        }
    }

    if (picking) {
        AppPickerDialog(
            title = "見張らないアプリ",
            selected = s.excludePackages,
            onToggle = { pkg ->
                val next = if (pkg in s.excludePackages) s.excludePackages - pkg else s.excludePackages + pkg
                update(s.copy(excludePackages = next))
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun NumberRow(
    head: String,
    value: Int,
    tail: String,
    step: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(head, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onChange((value - step).coerceAtLeast(min)) }, enabled = value > min) { Text("−") }
        Text("$value", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { onChange((value + step).coerceAtMost(max)) }, enabled = value < max) { Text("+") }
        Text(tail, style = MaterialTheme.typography.bodyMedium)
    }
}
