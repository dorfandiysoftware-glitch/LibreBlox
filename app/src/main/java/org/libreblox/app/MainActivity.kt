package org.libreblox.app

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

class MainActivity : ComponentActivity() {
    private val vm by viewModels<AppVm>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("lb", Context.MODE_PRIVATE)
        setContent {
            var theme by remember { mutableIntStateOf(prefs.getInt("theme", 0)) }
            var dynamic by remember { mutableStateOf(prefs.getBoolean("dynamic", true)) }
            LibreTheme(theme, dynamic) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Root(
                        vm, theme, dynamic,
                        onTheme = { theme = it; prefs.edit().putInt("theme", it).apply() },
                        onDynamic = { dynamic = it; prefs.edit().putBoolean("dynamic", it).apply() },
                    )
                }
            }
        }
    }
}

@Composable
fun LibreTheme(theme: Int, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (theme) { 1 -> false; 2, 3 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    var scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF66BB6A), secondary = Color(0xFF81C784))
        else -> lightColorScheme(primary = Color(0xFF2E7D32), secondary = Color(0xFF388E3C))
    }
    if (theme == 3) scheme = scheme.copy(
        background = Color.Black, surface = Color.Black,
        surfaceContainer = Color(0xFF0E0E0E), surfaceContainerHigh = Color(0xFF161616), surfaceContainerHighest = Color(0xFF1E1E1E),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab { GAMES, PEOPLE, SETTINGS }

@Composable
private fun Root(vm: AppVm, theme: Int, dynamic: Boolean, onTheme: (Int) -> Unit, onDynamic: (Boolean) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.GAMES) }
    val top = vm.stack.lastOrNull()
    BackHandler(top != null) { vm.stack.removeAt(vm.stack.lastIndex) }
    when (top) {
        is Screen.GameS -> GameScreen(top.game, onBack = { vm.stack.removeAt(vm.stack.lastIndex) }, onUser = { vm.stack.add(Screen.UserS(it)) })
        is Screen.UserS -> UserScreen(top.id, onBack = { vm.stack.removeAt(vm.stack.lastIndex) },
            onGame = { vm.stack.add(Screen.GameS(it)) }, onUser = { vm.stack.add(Screen.UserS(it.id)) })
        Screen.LoginS -> LoginScreen(
            onBack = { vm.stack.removeAt(vm.stack.lastIndex) },
            onDone = { vm.stack.removeAt(vm.stack.lastIndex); vm.refreshAccount() },
        )
        null -> Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(tab == Tab.GAMES, { tab = Tab.GAMES }, { Icon(Icons.Default.Home, null) }, label = { Text(stringResource(R.string.tab_games)) })
                    NavigationBarItem(tab == Tab.PEOPLE, { tab = Tab.PEOPLE }, { Icon(Icons.Default.Person, null) }, label = { Text(stringResource(R.string.tab_people)) })
                    NavigationBarItem(tab == Tab.SETTINGS, { tab = Tab.SETTINGS }, { Icon(Icons.Default.Settings, null) }, label = { Text(stringResource(R.string.tab_settings)) })
                }
            },
        ) { pad ->
            val m = Modifier.padding(pad)
            when (tab) {
                Tab.GAMES -> GamesTab(vm, m) { vm.stack.add(Screen.GameS(it)) }
                Tab.PEOPLE -> PeopleTab(vm, m) { vm.stack.add(Screen.UserS(it.id)) }
                Tab.SETTINGS -> SettingsTab(
                    theme, dynamic, onTheme, onDynamic, m,
                    account = vm.account,
                    onLogin = { vm.stack.add(Screen.LoginS) },
                    onProfile = { vm.account?.let { vm.stack.add(Screen.UserS(it.id)) } },
                    onLogout = vm::logout,
                )
            }
        }
    }
}
