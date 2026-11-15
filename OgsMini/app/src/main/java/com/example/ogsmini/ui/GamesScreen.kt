package com.example.ogsmini.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.ogsmini.GamesUi
import com.example.ogsmini.core.GameSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    state: GamesUi,
    username: String,
    onOpen: (Long) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    var idText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мои партии") },
                actions = {
                    TextButton(onClick = onRefresh) { Text("Обновить") }
                    TextButton(onClick = onLogout) { Text("Выйти") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (username.isNotEmpty()) {
                Text(
                    "Вы вошли как $username",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = idText,
                    onValueChange = { idText = it.filter { c -> c.isDigit() }.take(12) },
                    label = { Text("Открыть партию по номеру") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { idText.toLongOrNull()?.let(onOpen) },
                    enabled = idText.isNotEmpty(),
                ) { Text("Открыть") }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (!state.loading && state.error == null && state.games.isEmpty()) {
                Text(
                    "Активных партий нет. Начните партию на online-go.com или в приложении OGS — она появится здесь.",
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.games, key = { it.id }) { g ->
                    GameRow(g, onOpen)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun GameRow(g: GameSummary, onOpen: (Long) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(g.id) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("${g.black} — ${g.white}", style = MaterialTheme.typography.titleMedium)
        val extra = if (g.name.isNotBlank()) " · ${g.name}" else ""
        Text("#${g.id} · ${g.width}×${g.height}$extra", style = MaterialTheme.typography.bodySmall)
        when (g.myTurn) {
            true -> Text("● Ваш ход", color = MaterialTheme.colorScheme.primary)
            false -> Text("Ход соперника")
            null -> Unit
        }
    }
}
