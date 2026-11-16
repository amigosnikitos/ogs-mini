package com.example.ogsmini.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ogsmini.GameUi
import com.example.ogsmini.core.Stone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
    ui: GameUi?,
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
                    GoBoardView(ui, onTap, Modifier.fillMaxWidth())
                    val role = when (ui.myColor) {
                        Stone.BLACK -> " · вы играете чёрными"
                        Stone.WHITE -> " · вы играете белыми"
                        null -> ""
                    }
                    Text("Ходов сыграно: ${ui.moveCount}$role", style = MaterialTheme.typography.bodySmall)
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
            }
        }
    }

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

/** Камень рисуется кружком с рамкой, а не символом: чёрный камень должен быть виден и на тёмном фоне. */
@Composable
private fun PlayerLine(stone: Stone, name: String, captures: Int, clock: String?, active: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(if (stone == Stone.BLACK) Color(0xFF111111) else Color(0xFFF7F7F2), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(name, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
        }
        Text(if (clock != null) "взято $captures · $clock" else "взято $captures")
    }
}
