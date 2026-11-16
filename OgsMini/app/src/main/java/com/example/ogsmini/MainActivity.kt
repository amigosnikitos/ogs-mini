package com.example.ogsmini

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ogsmini.ui.ChallengesScreen
import com.example.ogsmini.ui.CreateChallengeScreen
import com.example.ogsmini.ui.GameScreen
import com.example.ogsmini.ui.GamesScreen
import com.example.ogsmini.ui.LoginScreen
import com.example.ogsmini.ui.WaitingScreen

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleRedirect(intent)
        setContent {
            MaterialTheme {
                App(vm) { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
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
}

@Composable
private fun App(vm: AppViewModel, openUrl: (String) -> Unit) {
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

    BackHandler(enabled = screen is Screen.Game) { vm.closeGame() }
    BackHandler(enabled = screen is Screen.Challenges) { vm.closeChallenges() }
    BackHandler(enabled = screen is Screen.CreateChallenge) { vm.backFromCreate() }
    BackHandler(enabled = screen is Screen.Waiting) { vm.cancelWaiting() }

    when (val s = screen) {
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
            onLogout = { vm.logout() },
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
            onBack = vm::closeGame,
            onTap = vm::onBoardTap,
            onConfirm = vm::confirmMove,
            onPass = vm::pass,
            onResign = vm::resign,
            onAccept = vm::acceptRemoval,
        )
    }
}
