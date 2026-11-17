package com.example.ogsmini

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ogsmini.ui.AppDrawer
import com.example.ogsmini.ui.ChallengesScreen
import com.example.ogsmini.ui.CreateChallengeScreen
import com.example.ogsmini.ui.GameNav
import com.example.ogsmini.ui.GameScreen
import com.example.ogsmini.ui.GamesScreen
import com.example.ogsmini.ui.LoginScreen
import com.example.ogsmini.ui.WaitingScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Системные панели нужного цвета уже с первого кадра; при смене темы их обновляет эффект ниже
        applySystemBars(vm.darkTheme.value ?: systemIsDark())
        if (savedInstanceState == null) handleRedirect(intent)
        setContent {
            val chosen by vm.darkTheme.collectAsStateWithLifecycle()
            val dark = chosen ?: isSystemInDarkTheme()
            DisposableEffect(dark) {
                applySystemBars(dark)
                onDispose { }
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val background = MaterialTheme.colorScheme.background
                // Цвет окна под интерфейсом: чтобы не мелькал белый при смене темы и повороте экрана
                SideEffect { window.setBackgroundDrawable(ColorDrawable(background.toArgb())) }
                Surface(modifier = Modifier.fillMaxSize(), color = background) {
                    App(vm, dark) { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    /** Браузер вернул нас по gobo://callback?code=...&state=... */
    private fun handleRedirect(i: Intent?) {
        val data = i?.data ?: return
        if (data.scheme == "gobo" && data.host == "callback") {
            vm.handleRedirect(data)
            i.data = null // чтобы не обработать повторно при пересоздании Activity
        }
    }

    private fun systemIsDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Прозрачные системные панели; цвет значков (светлые/тёмные) выбирается по теме приложения, а не системы. */
    private fun applySystemBars(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
    }

    private companion object {
        val LIGHT_SCRIM = android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}

@Composable
private fun App(vm: AppViewModel, dark: Boolean, openUrl: (String) -> Unit) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val login by vm.login.collectAsStateWithLifecycle()
    val games by vm.games.collectAsStateWithLifecycle()
    val game by vm.game.collectAsStateWithLifecycle()
    val challenges by vm.challenges.collectAsStateWithLifecycle()
    val create by vm.create.collectAsStateWithLifecycle()
    val waiting by vm.waiting.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Меню закрывается при любой смене экрана
    LaunchedEffect(screen) { drawerState.close() }

    BackHandler(enabled = screen is Screen.Game) { vm.backFromGame() }
    BackHandler(enabled = screen is Screen.Challenges) { vm.closeChallenges() }
    BackHandler(enabled = screen is Screen.CreateChallenge) { vm.backFromCreate() }
    BackHandler(enabled = screen is Screen.Waiting) { vm.cancelWaiting() }
    // Последним: он главнее остальных, пока меню открыто, «назад» закрывает именно его
    BackHandler(enabled = drawerState.currentValue == DrawerValue.Open) {
        scope.launch { drawerState.close() }
    }

    AppDrawer(
        state = drawerState,
        gesturesEnabled = screen is Screen.Games,
        username = vm.username,
        darkTheme = dark,
        onGames = { scope.launch { drawerState.close() } },
        onToggleTheme = { vm.setDarkTheme(!dark) },
        onLogout = {
            scope.launch {
                drawerState.close()
                vm.logout()
            }
        },
    ) {
        when (screen) {
            is Screen.Login -> LoginScreen(
                state = login,
                initialClientId = vm.clientId,
                onBrowserLogin = { id -> vm.beginBrowserLogin(id)?.let(openUrl) },
                onPasswordLogin = { id, user, pass -> vm.passwordLogin(id, user, pass) },
            )

            is Screen.Games -> GamesScreen(
                state = games,
                username = vm.username,
                onOpen = vm::openGame,
                onRefresh = vm::loadGames,
                onMenu = { scope.launch { drawerState.open() } },
                onChallenges = vm::openChallenges,
                onCreate = vm::openCreate,
            )

            is Screen.Challenges -> ChallengesScreen(
                state = challenges,
                onBack = vm::closeChallenges,
                onCreate = vm::openCreate,
                onTab = vm::setTab,
                onFilter = vm::setFilter,
                onAccept = vm::acceptChallenge,
                onCancel = vm::cancelChallenge,
                onDismissError = vm::dismissChallengesError,
            )

            is Screen.CreateChallenge -> CreateChallengeScreen(
                state = create,
                onBack = vm::backFromCreate,
                onChange = vm::updateForm,
                onRestrictRank = vm::setRestrictRank,
                onSubmit = vm::submitChallenge,
            )

            is Screen.Waiting -> WaitingScreen(
                state = waiting,
                onCancel = vm::cancelWaiting,
            )

            is Screen.Game -> GameScreen(
                ui = game,
                nav = GameNav(
                    onFirst = vm::viewFirst,
                    onPrev = vm::viewPrev,
                    onNext = vm::viewNext,
                    onLast = vm::viewLast,
                    onSeek = vm::viewSeek,
                    onLive = vm::viewLive,
                    onToggleAnalysis = vm::toggleAnalysis,
                    onAnalysisPass = vm::analysisPass,
                    onDeleteBranch = vm::deleteBranch,
                    onSiblingPrev = vm::siblingPrev,
                    onSiblingNext = vm::siblingNext,
                    onNode = vm::viewNode,
                    onOpenTree = vm::openTree,
                    onCloseTree = vm::closeTree,
                ),
                onBack = vm::backFromGame,
                onTap = vm::onBoardTap,
                onConfirm = vm::confirmMove,
                onPass = vm::pass,
                onResign = vm::resign,
                onAccept = vm::acceptRemoval,
            )
        }
    }
}
