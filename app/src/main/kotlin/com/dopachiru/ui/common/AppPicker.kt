package com.dopachiru.ui.common

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dopachiru.data.AppUsageRanking
import com.dopachiru.ui.rules.AppInfo
import com.dopachiru.ui.rules.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** アプリ一覧の並べ方。 */
enum class AppSort(val label: String) {
    /** ABC → あいうえお。Collator に任せる。 */
    NAME("名前順"),

    /** 使用時間の多い順。制限したいアプリはたいてい上に来る。 */
    USAGE("使用時間"),

    /** 選択済みを先頭に。何を選んだか見失わないように。 */
    SELECTED("選択中が上"),
}

/**
 * アプリを選ぶ一覧。検索・並べ替え・アイコン付き。
 *
 * ルールの対象、除外、タグのメンバー ── 選ばせる場面はどれも同じ形なので1つにまとめてある。
 */
@Composable
fun AppPickerList(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 400.dp,
) {
    val context = LocalContext.current
    val apps = remember { InstalledApps.load(context) }
    val usage = remember { AppUsageRanking.load(context) }
    val usageAccess = remember { AppUsageRanking.hasUsageAccess(context) }

    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(if (usage.isEmpty()) AppSort.NAME else AppSort.USAGE) }

    val visible = remember(query, sort, apps, usage, selected) {
        val filtered = if (query.isBlank()) {
            apps
        } else {
            apps.filter { it.label.contains(query, ignoreCase = true) }
        }
        when (sort) {
            AppSort.NAME -> InstalledApps.sortedByName(filtered)
            AppSort.USAGE -> InstalledApps.sortedByUsage(filtered, usage)
            AppSort.SELECTED -> InstalledApps.selectedFirst(filtered, selected)
        }
    }

    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("検索") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppSort.entries.forEach { option ->
                FilterChip(
                    selected = sort == option,
                    onClick = { sort = option },
                    label = { Text(option.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        // 使用時間で並べたいのにデータが無いときだけ、取り方を出す
        if (sort == AppSort.USAGE && !usageAccess) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (usage.isEmpty()) {
                    "使用時間の記録がまだありません。端末の使用状況を許可すると、" +
                        "ドパチルを入れる前のぶんも出ます。"
                } else {
                    "ドパチルが記録した直近48時間ぶんです。端末の使用状況を許可すると1週間ぶん出ます。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text("使用状況へのアクセスを開く", style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "${selected.size} 個選択中",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        LazyColumn(Modifier.heightIn(max = maxHeight)) {
            items(visible, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    checked = app.packageName in selected,
                    usageMinutes = usage[app.packageName] ?: 0,
                    onToggle = { onToggle(app.packageName) },
                )
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    checked: Boolean,
    usageMinutes: Int,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (usageMinutes > 0) {
                Text(
                    formatMinutes(usageMinutes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

/**
 * アプリのアイコン。
 *
 * 端末によっては数百件あるので、一覧に出たものだけを裏で読む。
 * 一度読んだものは [InstalledApps] のキャッシュから即座に出る。
 */
@Composable
fun AppIcon(packageName: String, size: Dp = 36.dp) {
    val context = LocalContext.current
    val icon by produceState(
        initialValue = InstalledApps.cachedIcon(packageName),
        packageName,
    ) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { InstalledApps.loadIcon(context, packageName) }
        }
    }

    Box(Modifier.size(size)) {
        val bitmap = icon
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 読み込み前・読めなかったときの受け皿。位置がずれないように場所だけ確保する
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {}
        }
    }
}

private fun formatMinutes(minutes: Int): String {
    if (minutes < 60) return "${minutes}分"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}時間" else "${h}時間${m}分"
}
