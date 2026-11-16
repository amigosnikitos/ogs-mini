package com.example.ogsmini.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Кнопка «гамбургер» для верхней панели: три полоски, рисуются сами (без библиотеки значков). */
@Composable
fun MenuButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = "Меню" },
    ) {
        val color = LocalContentColor.current
        Canvas(Modifier.size(20.dp)) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2f
            for (fraction in listOf(0.2f, 0.5f, 0.8f)) {
                val y = size.height * fraction
                drawLine(
                    color = color,
                    start = Offset(inset, y),
                    end = Offset(size.width - inset, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * Боковое меню: список разделов, переключатель темы и «Выйти».
 * Свайпом от левого края его можно открыть только там, где [gesturesEnabled] (на главном экране),
 * чтобы не мешать доске в партии.
 */
@Composable
fun AppDrawer(
    state: DrawerState,
    gesturesEnabled: Boolean,
    username: String,
    darkTheme: Boolean,
    onGames: () -> Unit,
    onToggleTheme: () -> Unit,
    onLogout: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = state,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(horizontal = 28.dp, vertical = 24.dp)) {
                    Text("OGS Mini", style = MaterialTheme.typography.titleLarge)
                    if (username.isNotEmpty()) {
                        Text(
                            username,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                NavigationDrawerItem(
                    label = { Text("Мои партии") },
                    selected = true,
                    onClick = onGames,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                NavigationDrawerItem(
                    label = { Text("Тёмная тема") },
                    selected = false,
                    onClick = onToggleTheme,
                    badge = { Switch(checked = darkTheme, onCheckedChange = null) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("Выйти") },
                    selected = false,
                    onClick = onLogout,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        },
        content = content,
    )
}
