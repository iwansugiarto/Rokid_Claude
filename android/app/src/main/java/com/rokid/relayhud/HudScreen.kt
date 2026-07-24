package com.rokid.relayhud

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Green = Color(0xFF00FF00)
val DimGreen = Color(0xFF7CE07C)   // 次要标签:调亮以便光学屏看清(原 2E7D2E 太暗)

data class HudLine(val text: String, val color: Color = Green)

/** 权限选择模式的当前状态(高亮项与剩余秒数由 MainActivity 更新)。 */
data class PermissionPrompt(
    val id: String,
    val summary: String,
    val options: List<String>,
    val allowKey: String,
    val highlight: Int,
    val secondsLeft: Int,
    val title: String = "需要确认",
)

class HudState {
    val lines = mutableStateListOf<HudLine>()
    var status by mutableStateOf("")              // 由 MainActivity 用 Strings 初始化
    val toolIndex = mutableMapOf<String, Int>()
    var recording by mutableStateOf(false)
    var choice by mutableStateOf<PermissionPrompt?>(null)
    var blanked by mutableStateOf(false)
    var statusline by mutableStateOf("")          // 由 MainActivity 用 Strings 初始化
    var scrollTick by mutableStateOf(0)
        private set
    var scrollDir = 0
        private set

    private var lastWasText = false

    fun add(text: String, color: Color = Green) { lines.add(HudLine(text, color)); lastWasText = false }

    /** 流式正文:按换行拆成多行(每项都矮于屏,可正确滚到底);首段续接上一条 text(跨 delta 同一行不断裂)。 */
    fun addText(text: String) {
        val parts = text.split("\n")
        parts.forEachIndexed { i, part ->
            if (i == 0 && lastWasText && lines.isNotEmpty()) {
                val last = lines.size - 1
                lines[last] = lines[last].copy(text = lines[last].text + part)
            } else {
                lines.add(HudLine(part, Green))
            }
        }
        lastWasText = true
    }

    fun clear() { lines.clear(); toolIndex.clear(); lastWasText = false }
    /** dir>0 回到底(恢复跟随),dir<0 上翻一屏。 */
    fun scroll(dir: Int) { scrollDir = dir; scrollTick++ }
}

@Composable
fun HudScreen(state: HudState, connStatus: String, s: Strings, connected: Boolean) {
    val listState = rememberLazyListState()
    var following by remember { mutableStateOf(true) }

    LaunchedEffect(state.lines.size) {
        if (following && state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.size - 1)
    }
    LaunchedEffect(state.scrollTick) {
        if (state.scrollTick == 0) return@LaunchedEffect
        val page = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        if (state.scrollDir > 0) {
            // 前滑=往下翻一屏;翻到底则恢复自动跟随
            val target = listState.firstVisibleItemIndex + page
            if (target >= state.lines.size - 1) {
                following = true
                if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.size - 1)
            } else {
                following = false
                listState.animateScrollToItem(target)
            }
        } else {
            // 后滑=往上翻一屏
            following = false
            listState.animateScrollToItem((listState.firstVisibleItemIndex - page).coerceAtLeast(0))
        }
    }

    val body = TextStyle(color = Green, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
    val meta = TextStyle(color = DimGreen, fontFamily = FontFamily.Monospace, fontSize = 13.sp)

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 44.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.crab_mark),
                    contentDescription = null,
                    modifier = Modifier.size(width = 21.dp, height = 14.dp).padding(end = 5.dp),
                )
                Text("Rokid Claude", style = meta.copy(color = Green))
                Text(" · $connStatus", style = meta)
            }
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .border(1.dp, Green, RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(state.status, style = meta)
                        if (state.recording) Text(s.recordingDot, style = body.copy(fontSize = 12.sp))
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)
                    ) {
                        items(state.lines) { line -> Text(line.text, color = line.color, style = body) }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(state.statusline, style = meta.copy(color = Green), maxLines = 1, softWrap = false)
                Text(if (connected) s.hint else s.offlineHint, style = meta.copy(fontSize = 11.sp))
            }
        }

        state.choice?.let { p ->
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.fillMaxWidth(0.86f)
                        .border(1.dp, Green, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    Text("${p.title} (${p.secondsLeft}s)", style = meta)
                    Text(p.summary, style = body.copy(fontSize = 14.sp), modifier = Modifier.padding(vertical = 6.dp))
                    // 窗口化:长列表(会话)只画高亮附近 5 行,上下用 ↑n/↓n 提示还有多少;短列表(模型/权限)原样全画
                    val win = 5
                    val first = (p.highlight - win / 2).coerceIn(0, (p.options.size - win).coerceAtLeast(0))
                    val last = (first + win - 1).coerceAtMost(p.options.size - 1)
                    if (first > 0) Text("   ↑ $first", style = body.copy(color = DimGreen))
                    for (i in first..last) {
                        val sel = i == p.highlight
                        Text(
                            (if (sel) "▸ " else "   ") + p.options[i],
                            style = body.copy(color = if (sel) Green else DimGreen),
                            maxLines = 1,
                        )
                    }
                    if (last < p.options.size - 1) Text("   ↓ ${p.options.size - 1 - last}", style = body.copy(color = DimGreen))
                    Text(s.choiceHint, style = meta, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        if (state.blanked) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
    }
}

/** 模型 id 或别名 → 短名;组装 statusline 文本。tokens 以 k 显示。 */
fun shortModel(model: String?, s: Strings): String = when {
    model == null -> s.modelUnknown
    model.contains("opus", true) -> "opus"
    model.contains("sonnet", true) -> "sonnet"
    model.contains("haiku", true) -> "haiku"
    else -> model
}
fun statuslineText(model: String?, costUsd: Double, tokens: Long, s: Strings): String {
    val cost = String.format("$%.2f", costUsd)
    val tok = if (tokens >= 1000) "${tokens / 1000}k tok" else "$tokens tok"
    return "${shortModel(model, s)} · ${s.sessionLabel} $cost · $tok"
}
