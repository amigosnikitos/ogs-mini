package com.example.ogsmini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ogsmini.LoginUi

@Composable
fun LoginScreen(
    state: LoginUi,
    initialClientId: String,
    onBrowserLogin: (clientId: String) -> Unit,
    onPasswordLogin: (clientId: String, user: String, password: String) -> Unit,
) {
    var clientId by remember { mutableStateOf(initialClientId) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var usePassword by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("OGS Mini", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Вход через OAuth2. Один раз зарегистрируйте своё приложение на " +
                    "online-go.com/oauth2/applications (шаги — в README) и вставьте Client ID.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("Client ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onBrowserLogin(clientId) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Войти через браузер") }

            TextButton(onClick = { usePassword = !usePassword }) {
                Text(if (usePassword) "Скрыть вход по паролю" else "Войти по паролю")
            }
            if (usePassword) {
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Имя пользователя") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onPasswordLogin(clientId, user, pass) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Войти") }
            }

            if (state.busy) CircularProgressIndicator()
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
