package com.dopachiru.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 上下にスワイプして選ぶホイール。
 *
 * ステッパーだと分単位の調整に何十回もタップが要るので、
 * 細かい値を扱うパラメータはこちらで入力させる。
 */
@Composable
fun WheelPicker(
    value: Int,
    values: List<Int>,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: (Int) -> String = { it.toString() },
    visibleCount: Int = 5,
) {
    if (values.isEmpty()) return

    val itemHeight = 40.dp
    val half = visibleCount / 2
    val initialIndex = values.indexOf(value).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val fling = rememberSnapFlingBehavior(lazyListState = listState)

    // 上下に half 個ぶんの余白を入れてあるので、先頭に見えている項目が中央に来る
    val centeredIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    // 指が離れて落ち着いたところで確定させる
    LaunchedEffect(listState, values) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) {
                    values.getOrNull(listState.firstVisibleItemIndex)?.let {
                        if (it != value) onValueChange(it)
                    }
                }
            }
    }

    // 外から値が変わったら追随する(操作中は邪魔しない)
    LaunchedEffect(value) {
        val target = values.indexOf(value)
        if (target >= 0 && !listState.isScrollInProgress && listState.firstVisibleItemIndex != target) {
            listState.scrollToItem(target)
        }
    }

    Box(
        modifier = modifier.height(itemHeight * visibleCount),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    RoundedCornerShape(10.dp),
                )
        )
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = itemHeight * half),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(values.size) { index ->
                val selected = index == centeredIndex
                Box(
                    modifier = Modifier
                        .height(itemHeight)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(values[index]),
                        fontSize = if (selected) 22.sp else 17.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                    )
                }
            }
        }
    }
}

/** 時 + 分 のホイール。分は1分刻み。 */
@Composable
fun HourMinutePicker(
    hour: Int,
    minute: Int,
    onChange: (hour: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier,
    maxHour: Int = 23,
    hourSuffix: String = "時",
    minuteSuffix: String = "分",
) {
    val hours = remember(maxHour) { (0..maxHour).toList() }
    val minutes = remember { (0..59).toList() }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        WheelPicker(
            value = hour.coerceIn(0, maxHour),
            values = hours,
            onValueChange = { onChange(it, minute) },
            label = { "$it" },
            modifier = Modifier.width(84.dp),
        )
        Text(
            hourSuffix,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        WheelPicker(
            value = minute.coerceIn(0, 59),
            values = minutes,
            onValueChange = { onChange(hour, it) },
            label = { "%02d".format(it) },
            modifier = Modifier.width(84.dp),
        )
        Text(
            minuteSuffix,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
