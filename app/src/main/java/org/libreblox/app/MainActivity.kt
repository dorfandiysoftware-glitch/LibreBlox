package org.libreblox.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm by viewModels<AppVm>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val cfg = Cfg(this)
        setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalCfg provides cfg,
                LocalDensity provides Density(d.density, d.fontScale * cfg.textScale),
            ) {
                LibreTheme(cfg.theme()) {
                    Surface(color = MaterialTheme.colorScheme.background) { Root(vm, cfg) }
                }
            }
        }
    }
}

@Composable
fun LibreTheme(spec: ThemeSpec, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = if (spec.id == "system") isSystemInDarkTheme() else spec.dark
    val base = when {
        spec.id == "system" && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    fun c(k: String, d: Color) = spec.colors[k]?.let { Color(it) } ?: d
    val scheme = base.copy(
        primary = c("primary", base.primary), onPrimary = c("onPrimary", base.onPrimary),
        secondary = c("secondary", base.secondary),
        background = c("background", base.background), onBackground = c("onBackground", base.onBackground),
        surface = c("surface", base.surface), onSurface = c("onSurface", base.onSurface),
        onSurfaceVariant = c("onSurfaceVariant", base.onSurfaceVariant), error = c("error", base.error),
        surfaceContainer = c("surfaceContainer", base.surfaceContainer),
        surfaceContainerHigh = c("surfaceContainerHigh", base.surfaceContainerHigh),
        surfaceContainerHighest = c("surfaceContainerHighest", base.surfaceContainerHighest),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(vm: AppVm, cfg: Cfg) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val tap = rememberTap(haptic, cfg)
    var tab by rememberSaveable { mutableStateOf("home") }
    var homeTab by rememberSaveable { mutableStateOf(0) }
    var chatFilter by rememberSaveable { mutableStateOf("") }
    var showSwitch by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val top = vm.stack.lastOrNull()
    val pop = { vm.stack.removeAt(vm.stack.lastIndex); Unit }
    val push = { s: Screen -> vm.stack.add(s); Unit }
    val login = { push(Screen.LoginS) }
    BackHandler(top != null) { pop() }
    BackHandler(top == null && drawer.isOpen) { scope.launch { drawer.close() } }

    when (top) {
        is Screen.GameS -> GameScreen(top.game, pop, onUser = { push(Screen.UserS(it)) })
        is Screen.UserS -> UserScreen(top.id, pop, onGame = { push(Screen.GameS(it)) }, onUser = { push(Screen.UserS(it.id)) })
        is Screen.ChatS -> ChatScreen(vm, top.convo, pop)
        Screen.LoginS -> LoginScreen(pop, onDone = { pop(); vm.refreshAccount() })
        Screen.SearchS -> SearchScreen(vm, pop, { push(Screen.GameS(it)) }, { push(Screen.UserS(it.id)) })
        Screen.SettingsS -> SettingsScreen(vm, pop) { push(Screen.DocsS) }
        Screen.DocsS -> DocsScreen(pop)
        Screen.ContinueS -> ContinueScreen(pop) { push(Screen.GameS(it)) }
        Screen.FriendsS -> FriendsScreen(vm, pop, { push(Screen.UserS(it)) }, { login() })
        Screen.AddFriendsS -> AddFriendsScreen(vm, pop) { push(Screen.UserS(it)) }
        Screen.NewChatS -> NewChatScreen(vm, pop)
        null -> {
            fun go(id: String) {
                tap()
                scope.launch { drawer.close() }
                when (id) {
                    "continue" -> push(Screen.ContinueS)
                    "friends" -> push(Screen.FriendsS)
                    "settings" -> push(Screen.SettingsS)
                    "messages" -> tab = "chat"
                    "switch" -> showSwitch = true
                    "logout" -> vm.logout()
                    "favorites" -> openWeb(ctx, "https://www.roblox.com/discover/#/sortName/v2/Favorites")
                    "communities" -> openWeb(ctx, "https://www.roblox.com/communities")
                    "inventory" -> openWeb(ctx, "https://www.roblox.com/users/${vm.account?.id ?: 0}/inventory")
                    "trades" -> openWeb(ctx, "https://www.roblox.com/trades")
                    "help" -> openWeb(ctx, "https://en.help.roblox.com/")
                    "learn" -> openWeb(ctx, "https://create.roblox.com/docs")
                    "create" -> openWeb(ctx, "https://create.roblox.com/")
                }
            }
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cfg.drawerAlpha)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            val me = vm.account
                            Column(Modifier.fillMaxWidth().padding(20.dp, 28.dp, 20.dp, 12.dp)) {
                                if (me == null) {
                                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                                    TextButton({ scope.launch { drawer.close() }; login() }) { Text(stringResource(R.string.login)) }
                                } else {
                                    AsyncImage(me.avatar, null, Modifier.size(64.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                                    Spacer(Modifier.height(10.dp))
                                    Text(me.display, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("@${me.name}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    vm.balance?.let { Text(stringResource(R.string.robux_fmt, compact(it)), color = RobloxGreen) }
                                }
                            }
                            cfg.drawerOrder.filter { it !in cfg.drawerHidden }.forEach { id ->
                                if (me == null && id in listOf("switch", "logout", "inventory", "trades")) return@forEach
                                NavigationDrawerItem(
                                    label = { Text(stringResource(drawerLabel(id))) }, selected = false, onClick = { go(id) },
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                            }
                        }
                    }
                },
            ) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            title = {},
                            navigationIcon = {
                                if (tab == "home") IconButton({ tap(); scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, null) }
                            },
                            actions = {
                                when (tab) {
                                    "chat" -> {
                                        IconButton({ tap(); push(Screen.SearchS) }) { Icon(Icons.Default.Search, null) }
                                        IconButton({ tap(); push(Screen.NewChatS) }) { Icon(Icons.Default.Add, null) }
                                    }
                                    "home" -> {
                                        IconButton({ tap(); push(Screen.SearchS) }) { Icon(Icons.Default.Search, null) }
                                        IconButton({ tap(); openWeb(ctx, "https://www.roblox.com/upgrades/robux") }) { Icon(Icons.Default.ShoppingCart, null) }
                                        IconButton({ tap(); tab = "chat" }) { Icon(Icons.Default.Email, null) }
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            cfg.navOrder.filter { it !in cfg.navHidden }.forEach { id ->
                                val icon = when (id) { "home" -> Icons.Default.Home; "chat" -> Icons.Default.Email; else -> Icons.Default.Person }
                                val label = when (id) { "home" -> R.string.tab_home; "chat" -> R.string.tab_chat; else -> R.string.tab_me }
                                NavigationBarItem(tab == id, { tap(); tab = id }, { Icon(icon, null) }, label = { Text(stringResource(label)) })
                            }
                        }
                    },
                ) { pad ->
                    val m = Modifier.padding(pad)
                    when (if (tab in cfg.navOrder && tab !in cfg.navHidden) tab else cfg.navOrder.first { it !in cfg.navHidden }) {
                        "home" -> Column(m) {
                            TabRow(homeTab) {
                                Tab(homeTab == 0, { homeTab = 0 }, text = { Text(stringResource(R.string.for_you)) })
                                Tab(homeTab == 1, { homeTab = 1 }, text = { Text(stringResource(R.string.charts)) })
                            }
                            HomeTab(
                                vm, Modifier, forYou = homeTab == 0,
                                onGame = { push(Screen.GameS(it)) }, onUser = { push(Screen.UserS(it)) },
                                onLogin = { login() }, onAddFriends = { push(Screen.AddFriendsS) }, onContinue = { push(Screen.ContinueS) },
                            )
                        }
                        "chat" -> ChatTab(vm, m, chatFilter, { push(Screen.ChatS(it)) }, { login() })
                        else -> MeTab(vm, m, onLogin = { login() }, onProfile = { vm.account?.let { push(Screen.UserS(it.id)) } })
                    }
                }
            }
        }
    }

    if (showSwitch) {
        val accs = Session.accounts(ctx)
        AlertDialog(
            onDismissRequest = { showSwitch = false },
            title = { Text(stringResource(R.string.drawer_switch)) },
            text = {
                Column {
                    accs.forEach { a ->
                        Text(
                            a.display + "  @" + a.name + if (a.id == Session.activeId(ctx)) "  ✓" else "",
                            Modifier.fillMaxWidth().clickable { showSwitch = false; vm.switchAccount(a.id) }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton({ showSwitch = false; login() }) { Text(stringResource(R.string.add_account)) } },
            dismissButton = { TextButton({ showSwitch = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
