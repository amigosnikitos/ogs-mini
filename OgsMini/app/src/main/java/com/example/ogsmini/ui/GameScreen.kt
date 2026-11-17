package com.example.ogsmini.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ogsmini.GameUi
import com.example.ogsmini.core.Coord
import com.example.ogsmini.core.NodeInfo
import com.example.ogsmini.core.Stone
import kotlin.math.roundToInt

/** Всё, что экран партии вызывает для просмотра истории и анализа (варианты). */
class GameNav(
    val onFirst: () -> Unit,
    val onPrev: () -> Unit,
    val onNext: () -> Unit,
    val onLast: () -> Unit,
    val onSeek: (Int) -> Unit,
    /** К текущей позиции партии; анализ выключается. */
    val onLive: () -> Unit,
    val onToggleAnalysis: () -> Unit,
    val onAnalysisPass: () -> Unit,
    val onDeleteBranch: () -> Unit,
    val onSiblingPrev: () -> Unit,
    val onSiblingNext: () -> Unit,
    /** Перейти к узлу дерева по номеру (из списка ходов или из «Дальше можно пойти»). */
    val onNode: (Int) -> Unit,
    val onOpenTree: () -> Unit,
    val onCloseTree: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
    ui: GameUi?,
    nav: GameNav,
    onBack: () -> Unit,
    onTap: (Int, Int) -> Unit,
    onConfirm: () -> Unit,
    onPass: () -> Unit,
    onResign: () -> Unit,
    onAccept: () -> Unit,
) {
    var askResign by remember { mutableStateOf(false) }
    var askPass by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ui != null && ui.title.isNotBlank()) ui.title else "Партия") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Назад") } },
            )
        },
    ) { padding ->
        if (ui == null) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val playing = ui.phase == "play"
                PlayerLine(Stone.BLACK, ui.blackName, ui.blackCaptures, ui.blackClock, playing && ui.toPlay == Stone.BLACK)
                PlayerLine(Stone.WHITE, ui.whiteName, ui.whiteCaptures, ui.whiteClock, playing && ui.toPlay == Stone.WHITE)
                Text(ui.status, style = MaterialTheme.typography.titleMedium)

                if (ui.loaded) {
                    ViewBanner(ui, nav)
                    // В анализе доска обведена рамкой: сразу видно, что это не настоящая партия
                    val frame = if (ui.analysis) Modifier.border(3.dp, MaterialTheme.colorScheme.tertiary) else Modifier
                    Box(modifier = Modifier.fillMaxWidth().then(frame)) {
                        GoBoardView(ui, onTap, Modifier.fillMaxWidth())
                    }
                    MoveNavigator(ui, nav)
                    if (ui.analysis) {
                        AnalysisTools(ui, nav)
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (ui.phase == "stone removal") {
                                Button(
                                    onClick = onAccept,
                                    enabled = ui.canRemove,
                                    modifier = Modifier.weight(1f),
                                ) { Text("Принять") }
                            } else {
                                Button(
                                    onClick = onConfirm,
                                    enabled = ui.canMove && ui.pending >= 0,
                                    modifier = Modifier.weight(1f),
                                ) { Text("Подтвердить") }
                                OutlinedButton(
                                    onClick = { askPass = true },
                                    enabled = ui.canMove,
                                    modifier = Modifier.weight(1f),
                                ) { Text("Пас") }
                            }
                            OutlinedButton(
                                onClick = { askResign = true },
                                enabled = ui.myColor != null && playing,
                                modifier = Modifier.weight(1f),
                            ) { Text("Сдаться") }
                        }
                    }
                    val role = when (ui.myColor) {
                        Stone.BLACK -> " · вы играете чёрными"
                        Stone.WHITE -> " · вы играете белыми"
                        null -> ""
                    }
                    Text("Ходов сыграно: ${ui.moveCount}$role", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (ui != null && ui.showTree) TreeDialog(ui, nav)

    if (askPass) {
        AlertDialog(
            onDismissRequest = { askPass = false },
            title = { Text("Сделать пас?") },
            confirmButton = {
                TextButton(onClick = {
                    askPass = false
                    onPass()
                }) { Text("Пас") }
            },
            dismissButton = { TextButton(onClick = { askPass = false }) { Text("Отмена") } },
        )
    }
    if (askResign) {
        AlertDialog(
            onDismissRequest = { askResign = false },
            title = { Text("Сдаться?") },
            text = { Text("Партия будет засчитана как проигранная.") },
            confirmButton = {
                TextButton(onClick = {
                    askResign = false
                    onResign()
                }) { Text("Сдаться") }
            },
            dismissButton = { TextButton(onClick = { askResign = false }) { Text("Отмена") } },
        )
    }
}

// ---------------------------------------------------------------------- просмотр истории и анализ

/** Полоса над доской, когда показана не текущая позиция партии. */
@Composable
private fun ViewBanner(ui: GameUi, nav: GameNav) {
    if (ui.analysis) {
        BannerRow("Анализ: камни строят вариант только у вас на экране, на сервер ничего не уходит", "К партии", nav.onLive)
    } else if (!ui.live) {
        BannerRow("Просмотр истории: ход ${ui.viewMove} из ${ui.trunkLength}", "К текущей", nav.onLive)
    }
}

@Composable
private fun BannerRow(text: String, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAction) { Text(action) }
    }
}

/** Номер хода, кнопки «в начало / назад / вперёд / в конец», ползунок, переключатель анализа и список ходов. */
@Composable
private fun MoveNavigator(ui: GameUi, nav: GameNav) {
    val base = if (ui.inVariation) "Вариант · ход ${ui.viewMove}" else "Ход ${ui.viewMove} из ${ui.trunkLength}"
    val counter = if (ui.viewMoveLabel.isNotEmpty()) "$base · ${ui.viewMoveLabel}" else base
    Text(
        counter,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NavButton(NavIcon.FIRST, ui.canPrev, nav.onFirst, Modifier.weight(1f))
        NavButton(NavIcon.PREV, ui.canPrev, nav.onPrev, Modifier.weight(1f))
        NavButton(NavIcon.NEXT, ui.canNext, nav.onNext, Modifier.weight(1f))
        NavButton(NavIcon.LAST, ui.canNext, nav.onLast, Modifier.weight(1f))
    }
    MoveSlider(ui, nav.onSeek)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleChip("Анализ", ui.analysis, nav.onToggleAnalysis)
        OutlinedButton(onClick = nav.onOpenTree) {
            Text(if (ui.hasVariations) "Ходы и варианты" else "Список ходов")
        }
    }
}

/** Ползунок по ходам. Пока палец на нём, показывает его положение, а не позицию из модели — движение получается плавным. */
@Composable
private fun MoveSlider(ui: GameUi, onSeek: (Int) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val max = ui.lineLength.coerceAtLeast(1).toFloat()
    Slider(
        value = (drag ?: ui.viewMove.toFloat()).coerceIn(0f, max),
        onValueChange = { v ->
            drag = v
            val target = v.roundToInt()
            if (target != ui.viewMove) onSeek(target)
        },
        onValueChangeFinished = { drag = null },
        valueRange = 0f..max,
        enabled = ui.lineLength > 0 && !ui.inVariation,
    )
}

/** Инструменты режима анализа: пас, удаление ветки, соседние варианты и выбор продолжения. */
@Composable
private fun AnalysisTools(ui: GameUi, nav: GameNav) {
    val colorName = if (ui.analysisColor == Stone.BLACK) "чёрные" else "белые"
    Text(
        "Следующий камень — $colorName. Нажимайте на доску, чтобы строить вариант.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = nav.onAnalysisPass, modifier = Modifier.weight(1f)) { Text("Пас") }
        OutlinedButton(
            onClick = nav.onDeleteBranch,
            enabled = ui.canDelete,
            modifier = Modifier.weight(1f),
        ) { Text("Удалить ветку") }
    }
    if (ui.siblingCount > 1) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavButton(NavIcon.PREV, ui.siblingIndex > 1, nav.onSiblingPrev, Modifier.width(64.dp))
            Text(
                "Вариант ${ui.siblingIndex} из ${ui.siblingCount}",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            NavButton(NavIcon.NEXT, ui.siblingIndex < ui.siblingCount, nav.onSiblingNext, Modifier.width(64.dp))
        }
    }
    if (ui.choices.size >= 2) {
        Text("Отсюда можно пойти дальше:", style = MaterialTheme.typography.bodySmall)
        Choices(
            options = ui.choices.map { it.id to choiceText(it, ui.height) },
            selected = -1,
            onSelect = nav.onNode,
        )
    }
}

private fun choiceText(c: NodeInfo, height: Int): String {
    val m = c.move
    val where = if (m != null) Coord.label(m.x, m.y, height) else ""
    return "${c.number}. $where" + if (c.trunk) " · партия" else ""
}

// ---------------------------------------------------------------------- окно со списком ходов

@Composable
private fun TreeDialog(ui: GameUi, nav: GameNav) {
    val current = ui.outline.indexOfFirst { it.current }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 4).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = nav.onCloseTree,
        title = { Text(if (ui.hasVariations) "Ходы и варианты" else "Ходы партии") },
        text = {
            if (ui.outline.isEmpty()) {
                Text("Ходов пока нет.")
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    items(ui.outline, key = { it.id }) { row ->
                        OutlineRow(row, ui.height) { nav.onNode(row.id) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = nav.onCloseTree) { Text("Закрыть") } },
    )
}

/** Строка списка ходов; варианты сдвинуты вправо, ход партии, на котором стоит показ, подсвечен. */
@Composable
private fun OutlineRow(row: NodeInfo, height: Int, onClick: () -> Unit) {
    val m = row.move
    val where = if (m != null) Coord.label(m.x, m.y, height) else ""
    val background = if (row.current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val textColor = when {
        row.current -> MaterialTheme.colorScheme.onSecondaryContainer
        row.trunk -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.tertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = (8 + 16 * row.indent).dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (m != null) StoneDot(m.color, 12.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            "${row.number}. $where",
            color = textColor,
            fontWeight = if (row.current) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ---------------------------------------------------------------------- значки и мелочи

private enum class NavIcon { FIRST, PREV, NEXT, LAST }

/** Кнопка со значком, нарисованным вручную: шрифтовые стрелки на разных телефонах выглядят по-разному. */
@Composable
private fun NavButton(icon: NavIcon, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.onSurface
    val color = if (enabled) base else base.copy(alpha = 0.38f)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Canvas(modifier = Modifier.size(20.dp)) { drawNavIcon(icon, color) }
    }
}

private fun DrawScope.drawNavIcon(icon: NavIcon, color: Color) {
    val w = size.width
    val h = size.height
    val bar = w * 0.16f
    fun triangle(tipX: Float, baseX: Float) {
        val p = Path()
        p.moveTo(tipX, h * 0.5f)
        p.lineTo(baseX, h * 0.12f)
        p.lineTo(baseX, h * 0.88f)
        p.close()
        drawPath(p, color)
    }
    when (icon) {
        NavIcon.PREV -> triangle(w * 0.2f, w * 0.8f)
        NavIcon.NEXT -> triangle(w * 0.8f, w * 0.2f)
        NavIcon.FIRST -> {
            drawRect(color, topLeft = Offset(w * 0.08f, h * 0.12f), size = Size(bar, h * 0.76f))
            triangle(w * 0.3f, w * 0.92f)
        }
        NavIcon.LAST -> {
            triangle(w * 0.7f, w * 0.08f)
            drawRect(color, topLeft = Offset(w * 0.92f - bar, h * 0.12f), size = Size(bar, h * 0.76f))
        }
    }
}

/** Камень рисуется кружком с рамкой, а не символом: чёрный камень должен быть виден и на тёмном фоне. */
@Composable
private fun StoneDot(stone: Stone, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(if (stone == Stone.BLACK) Color(0xFF111111) else Color(0xFFF7F7F2), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

@Composable
private fun PlayerLine(stone: Stone, name: String, captures: Int, clock: String?, active: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            StoneDot(stone, 14.dp)
            Spacer(Modifier.width(8.dp))
            Text(name, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
        }
        Text(if (clock != null) "взято $captures · $clock" else "взято $captures")
    }
}
