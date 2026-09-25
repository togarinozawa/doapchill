package com.dopachiru.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.dopachiru.runtime.DopaRuntime
import kotlinx.coroutines.launch

/**
 * 端末どうしの画面。名簿を見て、別の端末に頼む。
 *
 * ## 頼みであって命令ではない
 *
 * ここで押しても、向こうで**すぐ起きるとは限りません**。締めるほう(集中・閉め出し)は
 * 届きしだい走りますが、緩めるほう(止める・開ける・解禁券)は**向こうの関門を
 * 通ってから**になります。そこを画面でも隠さずに出します ── 「押したのに
 * 効かない」と思わせると、もう一度押すか、仕組みごと信じなくなります。
 *
 * ここを素通しにすると、PC の閉め出しはスマホを持っているだけで全部無効に
 * なります。閉め出しの意味は「いま解けないこと」なので、解ける口を1つ足せば
 * 全部消えます。
 */
@Composable
fun DeviceScreen() {
    val scope = rememberCoroutineScope()
    val roster by DopaRuntime.devices.collectAsState(initial = emptyList())
    val commands by DopaRuntime.commands.collectAsState(initial = emptyList())
    val me = DopaRuntime.myDeviceId

    val others = roster.filter { it.deviceId != me }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { SelfCard(roster.firstOrNull { it.deviceId == me }, me) }

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
                    "ほかの端末がまだ届いていません。向こうでも同期を設定して、" +
                        "一度つないでください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(others, key = { it.deviceId }) { device ->
            RemoteCard(device) { kind, minutes, reason ->
                scope.launch {
                    DopaRuntime.requestOnDevice(
                        to = device.deviceId,
                        kind = kind,
                        params = Params.of(CommandKind.KEY_MINUTES to minutes),
                        reason = reason,
                    )
                }
            }
        }

        val open = commands.filter { it.isOpen || it.handledAtSec > 0 }
            .sortedByDescending { it.issuedAtSec }
            .take(20)
        if (open.isNotEmpty()) {
            item {
                Text("やりとり", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            items(open, key = { it.uid }) { command ->
                CommandRow(command, me, roster) {
                    scope.launch { DopaRuntime.cancelCommand(command.uid) }
                }
            }
        }
    }
}

@Composable
private fun SelfCard(self: DeviceInfo?, myDeviceId: String) {
    val scope = rememberCoroutineScope()
    val stored by DopaRuntime.settings.deviceName.collectAsState(initial = "")
    var name by remember(stored) { mutableStateOf(stored) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("この端末", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (myDeviceId.isBlank()) "同期を設定していません" else myDeviceId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (myDeviceId.isNotBlank()) {
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
                        onClick = { scope.launch { DopaRuntime.settings.setDeviceName(name) } },
                        enabled = name != stored,
                    ) { Text("保存") }
                    OutlinedButton(
                        onClick = { scope.launch { DopaRuntime.sync.syncNow(); DopaRuntime.runInbox() } },
                    ) { Text("いま同期する") }
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
}

/** 頼める操作を並べた札。締めるほうと緩めるほうを**分けて**出す。 */
@Composable
private fun RemoteCard(
    device: DeviceInfo,
    onRequest: (kind: String, minutes: Int, reason: String) -> Unit,
) {
    var minutes by remember { mutableIntStateOf(30) }
    var reason by remember { mutableStateOf("") }
    val nowSec = System.currentTimeMillis() / 1000

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
            Text("長さ", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { minutes = (minutes - 5).coerceAtLeast(5) },
                    enabled = minutes > 5,
                ) { Text("−") }
                Text("$minutes 分", style = MaterialTheme.typography.titleMedium)
                TextButton(
                    onClick = { minutes = (minutes + 5).coerceAtMost(12 * 60) },
                ) { Text("+") }
            }

            Spacer(Modifier.height(12.dp))
            Text("締める", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                // 自分を縛るほうに摩擦を足すと、縛るのが面倒になって使わなくなる
                "届きしだい走ります。関門はありません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onRequest(CommandKind.FOCUS_START, minutes, "") }) {
                    Text("集中を始めさせる")
                }
                OutlinedButton(onClick = { onRequest(CommandKind.LOCK_NOW, minutes, "") }) {
                    Text("いま閉め出す")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("緩める", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "向こうの関門を通ってから効きます。待ち時間は**向こうが受け取ってから**" +
                    "数えます ── こちらの時計を戻しても飛ばせません。",
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
            Spacer(Modifier.height(4.dp))
            Text(
                "向こうに「理由を書く」関門があれば、ここに書いた文でそのまま通ります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onRequest(CommandKind.UNLOCK, minutes, reason) }) {
                    Text("閉まっているものを開ける")
                }
                OutlinedButton(onClick = { onRequest(CommandKind.PASS, minutes, reason) }) {
                    Text("解禁券を使わせる")
                }
            }
        }
    }
}

@Composable
private fun CommandRow(
    command: Command,
    myDeviceId: String,
    roster: List<DeviceInfo>,
    onCancel: () -> Unit,
) {
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
                TextButton(onClick = onCancel) { Text("取り下げる") }
            }
        }
    }
}
