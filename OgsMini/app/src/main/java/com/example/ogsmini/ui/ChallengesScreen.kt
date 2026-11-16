package com.example.ogsmini.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ogsmini.ChallengesUi
import com.example.ogsmini.core.ChallengeFilter
import com.example.ogsmini.core.ChallengeRow
import com.example.ogsmini.core.ChallengeText
import com.example.ogsmini.core.Ranks
import com.example.ogsmini.core.TimePresets

/** Список открытых вызовов — как «Available games» на сайте: можно принять чужой вызов или снять свой. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChallengesScreen(
    state: ChallengesUi,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onTab: (Int) -> Unit,
    onFilter: (ChallengeFilter) -> Unit,
    onAccept: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onDismissError: () -> Unit,
) {
    var showFilters by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ChallengeRow?>(null) }
    val rows = if (state.tab == 0) state.live else state.correspondence

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Открытые вызовы") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Назад") } },
                actions = { TextButton(onClick = onCreate) { Text("Создать") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (!state.connected) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Подключаюсь к серверу…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            state.error?.let { ErrorBox(it, onDismissError) }

            TabRow(selectedTabIndex = state.tab) {
                Tab(
                    selected = state.tab == 0,
                    onClick = { onTab(0) },
                    text = { Text("Быстрые (${state.live.size})") },
                )
                Tab(
                    selected = state.tab == 1,
                    onClick = { onTab(1) },
                    text = { Text("Заочные (${state.correspondence.size})") },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { showFilters = !showFilters }) {
                    Text(if (showFilters) "Фильтры ▴" else "Фильтры ▾")
                }
                Text(
                    "ваш ранг: ${Ranks.name(state.myRank)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            if (showFilters) FilterPanel(state.filter, onFilter)

            LazyColumn(Modifier.fillMaxSize()) {
                if (rows.isEmpty() && state.connected) {
                    item {
                        Text(
                            "Подходящих вызовов сейчас нет. Создайте свой — кнопка «Создать» сверху.",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(rows, key = { it.c.id }) { row ->
                    ChallengeItem(
                        row = row,
                        busy = state.busyId == row.c.id,
                        onOpen = { selected = row },
                        onCancel = { onCancel(row.c.id) },
                    )
                    HorizontalDivider()
                }
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.hiddenCount > 0) {
                            Text(
                                "Скрыто фильтрами: ${state.hiddenCount}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (state.rengoHidden > 0) {
                            Text(
                                "Командные вызовы (рэнго) не показаны: ${state.rengoHidden}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    selected?.let { row ->
        ChallengeDialog(
            row = row,
            canAccept = state.busyId == null,
            onClose = { selected = null },
            onAccept = {
                selected = null
                onAccept(row.c.id)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(f: ChallengeFilter, onFilter: (ChallengeFilter) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToggleChip("Недоступные", f.showIneligible) { onFilter(f.copy(showIneligible = !f.showIneligible)) }
        ToggleChip("Рейтинговые", f.showRanked) { onFilter(f.copy(showRanked = !f.showRanked)) }
        ToggleChip("Дружеские", f.showUnranked) { onFilter(f.copy(showUnranked = !f.showUnranked)) }
        ToggleChip("19×19", f.show19) { onFilter(f.copy(show19 = !f.show19)) }
        ToggleChip("13×13", f.show13) { onFilter(f.copy(show13 = !f.show13)) }
        ToggleChip("9×9", f.show9) { onFilter(f.copy(show9 = !f.show9)) }
        ToggleChip("Другие размеры", f.showOther) { onFilter(f.copy(showOther = !f.showOther)) }
        ToggleChip("С форой", f.showHandicap) { onFilter(f.copy(showHandicap = !f.showHandicap)) }
    }
}

@Composable
private fun ChallengeItem(row: ChallengeRow, busy: Boolean, onOpen: () -> Unit, onCancel: () -> Unit) {
    val c = row.c
    val background = if (row.mine) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(enabled = !row.mine, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (row.mine || row.eligible) 1f else 0.6f),
        ) {
            val title = if (row.mine) "Ваш вызов" else "${c.username} [${Ranks.label(c.rank, c.pro)}]"
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${ChallengeText.size(c)} · ${c.time.short()} · ${ChallengeText.ratedText(c.ranked)}")
            Text(
                "${ChallengeText.rules(c.rules)} · фора: ${ChallengeText.handicap(c.handicap)} · " +
                    "коми: ${ChallengeText.komi(c.komi)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (c.name.isNotBlank()) {
                Text(
                    c.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.mine) {
                Text("Ждёт соперника", style = MaterialTheme.typography.bodySmall)
            }
            row.reason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (row.mine) {
            OutlinedButton(onClick = onCancel, enabled = !busy) { Text(if (busy) "…" else "Снять") }
        } else if (row.eligible) {
            Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

/** Подробности вызова и кнопка «Принять» — как окно принятия партии на сайте. */
@Composable
private fun ChallengeDialog(row: ChallengeRow, canAccept: Boolean, onClose: () -> Unit, onAccept: () -> Unit) {
    val c = row.c
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${c.username} [${Ranks.label(c.rank, c.pro)}]") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (c.name.isNotBlank()) Text(c.name, fontWeight = FontWeight.Bold)
                DetailLine("Доска", ChallengeText.size(c))
                DetailLine("Время", "${TimePresets.systemName(c.time.system)}: ${c.time.short()}")
                if (c.time.pauseOnWeekends) DetailLine("Выходные", "время не идёт")
                DetailLine("Правила", ChallengeText.rules(c.rules))
                DetailLine("Игра", ChallengeText.ratedText(c.ranked))
                DetailLine("Фора", ChallengeText.handicap(c.handicap))
                DetailLine("Коми", ChallengeText.komi(c.komi))
                DetailLine("Ваш цвет", ChallengeText.yourColor(c.creatorColor))
                DetailLine("Анализ", if (c.disableAnalysis) "запрещён" else "разрешён")
                if (c.komi != null) {
                    Text(
                        "Коми задано вручную, проверьте его перед игрой.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                row.reason?.let {
                    Text(
                        "Принять нельзя: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (row.eligible) TextButton(onClick = onAccept, enabled = canAccept) { Text("Принять игру") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
}
