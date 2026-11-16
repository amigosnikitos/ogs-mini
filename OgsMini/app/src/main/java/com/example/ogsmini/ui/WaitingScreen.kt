package com.example.ogsmini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ogsmini.WaitingUi

/**
 * Вызов быстрой партии создан и ждёт соперника. Пока этот экран открыт, приложение раз в секунду
 * подтверждает серверу, что вызов жив; когда соперник примет его, откроется партия.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaitingScreen(state: WaitingUi?, onCancel: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ожидание соперника") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("← Отмена") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            if (state == null) {
                CircularProgressIndicator()
            } else {
                val error = state.error
                val who = state.opponent
                Text(
                    if (who != null) "Ждём ответа игрока $who" else "Ждём, пока кто-нибудь примет вызов",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(state.summary, textAlign = TextAlign.Center)
                if (error == null) {
                    CircularProgressIndicator()
                    Text("Прошло: ${state.seconds / 60}:${(state.seconds % 60).toString().padStart(2, '0')}")
                    if (!state.connected) {
                        Text(
                            "Нет связи с сервером, переподключаюсь…",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        "Не закрывайте приложение, пока вызов ждёт соперника: если связь пропадёт, сервер снимет вызов.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Отменить вызов") }
                } else {
                    ErrorBox(error)
                    Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Назад к вызовам") }
                }
            }
        }
    }
}
