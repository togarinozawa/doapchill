package com.dopachiru.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.model.Command
import com.dopachiru.core.model.CommandKind
import com.dopachiru.core.model.CommandState
import com.dopachiru.core.param.Params
import com.dopachiru.core.sync.DeviceInfo
import com.dopachiru.desktop.DesktopRuntime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 端末どうしの画面。Android 版と同じ約束を、同じ言葉で出します。
 *
 * 頼めるのは**頼みごと**であって命令ではありません。締めるほうは届きしだい走り、
 * 緩めるほうは相手の関門を通ってから効きます。判断は core の
 * [com.dopachiru.core.model.Commands.triage] に1つだけ置いてあります。
 */
@Composable
fun DeviceScreen() {
    val file by DesktopRuntime.ruleFile.collectAsState()
    val settings by DesktopRuntime.settings.collectAsState()
    val reservations by DesktopRuntime.reservations.collectAsState()
    val me = settings.sync.deviceId
    val roster = file.devices.sortedBy { it.displayName }
    val others = roster.filter { it.deviceId != me }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { SelfCard(me, settings.deviceName, roster.firstOrNull { it.deviceId == me }) }

        if (me.isBlank()) {
            item {
                Text(
                    "ほかの端末とつなぐと、ここに並びます。設定 → 端末の連携。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@LazyColumn
        }

        if (others.isEmpty()) {
            item {
                Text(
                    "ほかの端末がまだ届いていません。向こうでも同期を設定して、一度つないでください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(others, key = { it.deviceId }) { device -> RemoteCard(device) }

        // この PC に効く予約。入れるのはスマホ側なので、ここは見るだけ
        val mine = reservations.filter { it.appliesToDevice(me) }.sortedBy { it.startEpochSec }
        item {
            Text("この端末の予約", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        if (mine.isEmpty()) {
            item {
                Text(
                    "ありません。スマホの「予約」から、この端末を選んで入れられます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(mine, key = { it.uid }) { reservation ->
                val now = System.currentTimeMillis() / 1000
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            formatSpan(reservation.startEpochSec, reservation.endEpochSec),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            (if (reservation.coversAt(now)) "いま使えます ・ " else "") +
                                reservation.target.packages.joinToString("・").ifBlank { "対象なし" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        val log = file.commands.sortedByDescending { it.issuedAtSec }.take(20)
        if (log.isNotEmpty()) {
            item {
                Text("やりとり", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            items(log, key = { it.uid }) { command -> CommandRow(command, me, roster) }
        }
    }
}

@Composable
private fun SelfCard(myDeviceId: String, storedName: String, self: DeviceInfo?) {
    var name by remember(storedName) { mutableStateOf(storedName) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("この端末", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (myDeviceId.isBlank()) "同期を設定していません" else myDeviceId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (myDeviceId.isBlank()) return@Column

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("呼び名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                // deviceId を変えると実績の見出しが切れるので、呼び名だけ分けてある
                "名簿に出る名前です。変えても過去の記録は切れません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { DesktopRuntime.setDeviceName(name) },
                    enabled = name != storedName,
                ) { Text("保存") }
                OutlinedButton(onClick = { DesktopRuntime.syncInBackground() }) { Text("いま同期する") }
            }
            if (self != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "最後の同期: " + self.freshnessAt(System.currentTimeMillis() / 1000),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RemoteCard(device: DeviceInfo) {
    var minutes by remember { mutableIntStateOf(30) }
    var reason by remember { mutableStateOf("") }
    val nowSec = System.currentTimeMillis() / 1000

    fun request(kind: String, why: String = "") {
        DesktopRuntime.requestOnDevice(
            to = device.deviceId,
            kind = kind,
            params = Params.of(CommandKind.KEY_MINUTES to minutes),
            reason = why,
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(device.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                device.platform + " ・ 最後の同期 " + device.freshnessAt(nowSec) +
                    if (device.version.isNotBlank()) " ・ " + device.version else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("長さ ", style = MaterialTheme.typography.bodySmall)
                NumberStepper(
                    value = minutes, min = 5, max = 12 * 60, step = 5, suffix = "分",
                    onChange = { minutes = it },
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("締める", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                "届きしだい走ります。関門はありません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { request(CommandKind.FOCUS_START) }) { Text("集中を始めさせる") }
                OutlinedButton(onClick = { request(CommandKind.LOCK_NOW) }) { Text("いま閉め出す") }
            }

            Spacer(Modifier.height(16.dp))
            Text("緩める", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                "向こうの関門を通ってから効きます。待ち時間は向こうが受け取ってから数えます " +
                    "── こちらの時計を戻しても飛ばせません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("なぜ緩めたいか") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { request(CommandKind.UNLOCK, reason) }) {
                    Text("閉まっているものを開ける")
                }
                OutlinedButton(onClick = { request(CommandKind.PASS, reason) }) {
                    Text("解禁券を使わせる")
                }
            }
        }
    }
}

@Composable
private fun CommandRow(command: Command, myDeviceId: String, roster: List<DeviceInfo>) {
    val mine = command.from == myDeviceId
    val other = if (mine) command.to else command.from
    val name = roster.firstOrNull { it.deviceId == other }?.displayName ?: other

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                (if (mine) "→ $name " else "← $name ") + CommandKind.label(command.kind),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                CommandState.label(command.state) +
                    if (command.note.isNotBlank()) " ・ " + command.note else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (mine && command.isOpen) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { DesktopRuntime.cancelCommand(command.uid) }) { Text("取り下げる") }
            }
        }
    }
}

private val SPAN = DateTimeFormatter.ofPattern("M/d HH:mm")

private fun formatSpan(startSec: Long, endSec: Long): String {
    val zone = ZoneId.systemDefault()
    val from = Instant.ofEpochSecond(startSec).atZone(zone).format(SPAN)
    val to = Instant.ofEpochSecond(endSec).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    return "$from 〜 $to"
}
