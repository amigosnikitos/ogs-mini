package com.example.ogsmini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.ogsmini.CreateUi
import com.example.ogsmini.core.ChallengeText
import com.example.ogsmini.core.NewChallenge
import com.example.ogsmini.core.Ranks
import com.example.ogsmini.core.TimePresets

private val handicapOptions: List<Pair<Int, String>> =
    listOf(-1 to "авто", 0 to "нет") + (2..9).map { it to "$it" }

/** Форма «Создать вызов»: те же настройки, что в окне создания партии на сайте. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateChallengeScreen(
    state: CreateUi,
    onBack: () -> Unit,
    onChange: ((NewChallenge) -> NewChallenge) -> Unit,
    onRestrictRank: (Boolean) -> Unit,
    onSubmit: () -> Unit,
) {
    val f = state.form
    // Поля ввода держат свой текст у себя: так набор не «прыгает» при обновлении состояния
    var direct by remember { mutableStateOf(f.opponent.isNotEmpty()) }
    var opponentText by remember { mutableStateOf(f.opponent) }
    var nameText by remember { mutableStateOf(f.name) }
    var komiText by remember { mutableStateOf((f.customKomi ?: 6.5).toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Новый вызов") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Назад") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding() // клавиатура не закрывает поля ввода внизу формы
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Кому")
            Choices(listOf(false to "Любой игрок", true to "Конкретный игрок"), direct) { on ->
                direct = on
                onChange { it.copy(opponent = if (on) opponentText else "") }
            }
            if (direct) {
                OutlinedTextField(
                    value = opponentText,
                    onValueChange = { text ->
                        opponentText = text
                        onChange { it.copy(opponent = text) }
                    },
                    label = { Text("Имя игрока на OGS") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = nameText,
                onValueChange = { text ->
                    nameText = text
                    onChange { it.copy(name = text) }
                },
                label = { Text("Название (необязательно)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("Доска")
            Choices(listOf(9 to "9×9", 13 to "13×13", 19 to "19×19"), f.size) { size ->
                onChange { it.copy(size = size) }
            }

            SectionTitle("Время")
            Choices(TimePresets.speeds.map { it to TimePresets.speedName(it) }, f.time.speed) { speed ->
                onChange { it.copy(time = TimePresets.defaults(speed, it.time.system)) }
            }
            Choices(TimePresets.systems.map { it to TimePresets.systemName(it) }, f.time.system) { system ->
                onChange { it.copy(time = TimePresets.defaults(it.time.speed, system)) }
            }
            for (key in TimePresets.keys(f.time.system)) {
                val value = f.time.value(key)
                val range = TimePresets.countRange(f.time.speed, key)
                if (range != null) {
                    Stepper(
                        label = TimePresets.keyName(key),
                        valueText = "$value",
                        canDec = value > range.first,
                        canInc = value < range.last,
                        onDec = { onChange { it.copy(time = it.time.with(key, value - 1)) } },
                        onInc = { onChange { it.copy(time = it.time.with(key, value + 1)) } },
                    )
                } else {
                    val options = TimePresets.options(f.time.speed, f.time.system, key)
                    Picker(
                        label = TimePresets.keyName(key),
                        current = TimePresets.valueText(value),
                        options = options.map { it to TimePresets.valueText(it) },
                    ) { picked -> onChange { it.copy(time = it.time.with(key, picked)) } }
                }
            }
            if (f.time.speed == "correspondence") {
                SwitchRow("Не считать время на выходных", f.time.pauseOnWeekends) { on ->
                    onChange { it.copy(time = it.time.copy(pauseOnWeekends = on)) }
                }
            }

            SectionTitle("Партия")
            SwitchRow("Рейтинговая", f.ranked) { on -> onChange { it.copy(ranked = on) } }
            Picker(
                label = "Фора",
                current = ChallengeText.handicap(f.handicap),
                options = handicapOptions,
            ) { picked -> onChange { it.copy(handicap = picked) } }
            if (!f.ranked && f.handicap >= 0) {
                Choices(listOf(false to "Коми: авто", true to "Коми: своё"), f.customKomi != null) { custom ->
                    if (custom) komiText = (f.customKomi ?: 6.5).toString()
                    onChange { it.copy(customKomi = if (custom) (it.customKomi ?: 6.5) else null) }
                }
                if (f.customKomi != null) {
                    OutlinedTextField(
                        value = komiText,
                        onValueChange = { text ->
                            komiText = text
                            val parsed = text.replace(',', '.').toDoubleOrNull()
                            if (parsed != null) onChange { it.copy(customKomi = parsed) }
                        },
                        label = { Text("Коми") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Picker(
                label = "Правила",
                current = ChallengeText.rules(f.rules),
                options = NewChallenge.RULES.map { it to ChallengeText.rules(it) },
            ) { picked -> onChange { it.copy(rules = picked) } }
            Text("Ваш цвет")
            Choices(
                listOf("automatic" to "Авто", "black" to "Чёрные", "white" to "Белые", "random" to "Случайно"),
                f.color,
            ) { color -> onChange { it.copy(color = color) } }
            SwitchRow("Разрешить анализ в партии", !f.disableAnalysis) { on ->
                onChange { it.copy(disableAnalysis = !on) }
            }

            SectionTitle("Ранг соперника")
            SwitchRow("Ограничить (ваш ранг: ${Ranks.name(state.myRank)})", f.restrictRank) { on ->
                onRestrictRank(on)
            }
            if (f.restrictRank) {
                Stepper(
                    label = "Не слабее",
                    valueText = Ranks.name(f.minRank),
                    canDec = f.minRank > Ranks.MIN,
                    canInc = f.minRank < Ranks.MAX,
                    onDec = { onChange { it.copy(minRank = it.minRank - 1) } },
                    onInc = { onChange { it.copy(minRank = it.minRank + 1) } },
                )
                Stepper(
                    label = "Не сильнее",
                    valueText = Ranks.name(f.maxRank),
                    canDec = f.maxRank > Ranks.MIN,
                    canInc = f.maxRank < Ranks.MAX,
                    onDec = { onChange { it.copy(maxRank = it.maxRank - 1) } },
                    onInc = { onChange { it.copy(maxRank = it.maxRank + 1) } },
                )
            }

            Text(
                f.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let { ErrorBox(it) }
            val needName = direct && opponentText.isBlank()
            Button(
                onClick = onSubmit,
                enabled = !state.busy && !needName,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        state.busy -> "Создаю…"
                        needName -> "Введите имя игрока"
                        direct -> "Отправить вызов"
                        else -> "Создать вызов"
                    }
                )
            }
        }
    }
}
