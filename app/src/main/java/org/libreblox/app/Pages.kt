package org.libreblox.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
private fun Panel(content: @Composable () -> Unit) = Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } }

// ---------------- simple pushed screens ----------------

@Composable
fun SearchScreen(vm: AppVm, onBack: () -> Unit, onGame: (Game) -> Unit, onUser: (User) -> Unit) =
    DetailScaffold(stringResource(R.string.tab_search), onBack) { m -> SearchTab(vm, m, onGame, onUser) }

@Composable
fun ContinueScreen(onBack: () -> Unit, onGame: (Game) -> Unit) {
    val cfg = LocalCfg.current
    DetailScaffold(stringResource(R.string.continue_title), onBack) { m ->
        if (cfg.history.isEmpty()) Box(m) { Centered { Text(stringResource(R.string.continue_empty)) } }
        else Box(m) { GameGrid(cfg.history.map { it.asGame() }, onGame = onGame) }
    }
}

@Composable
fun FriendsScreen(vm: AppVm, onBack: () -> Unit, onUser: (Long) -> Unit, onLogin: () -> Unit) =
    DetailScaffold(stringResource(R.string.tab_friends), onBack) { m -> FriendsTab(vm, m, onUser, onLogin) }

@Composable
fun DocsScreen(onBack: () -> Unit) {
    val clip = LocalClipboardManager.current
    DetailScaffold(stringResource(R.string.theme_docs), onBack) { m ->
        Column(m.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectionContainer { Text(THEME_DOCS, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton({ clip.setText(AnnotatedString(themeToJson(BUILTIN_THEMES[1]))) }) { Text(stringResource(R.string.theme_copy_example)) }
        }
    }
}

@Composable
fun AddFriendsScreen(vm: AppVm, onBack: () -> Unit, onUser: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<Load<List<User>>>(Load.Idle) }
    val sent = remember { mutableStateListOf<Long>() }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadRequests() }
    fun act(id: Long, action: String, done: () -> Unit) {
        val c = vm.cookie() ?: return
        scope.launch {
            try { Api.friendAction(id, action, c); error = null; done() } catch (e: Exception) { error = e.message }
        }
    }
    fun search() {
        val q = query.trim()
        if (q.isEmpty()) { results = Load.Idle; return }
        results = Load.Busy
        scope.launch { results = try { Load.Ok(Api.searchUsers(q)) } catch (e: Exception) { Load.Err(e.message ?: "") } }
    }
    DetailScaffold(stringResource(R.string.add_friends), onBack) { m ->
        if (vm.account == null) { Box(m) { NeedLogin { vm.stack.add(Screen.LoginS) } }; return@DetailScaffold }
        LazyColumn(m.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { SearchField(query, stringResource(R.string.search_people), { query = it }, ::search, Modifier.padding(16.dp, 8.dp)) }
            error?.let { e -> item { Text(e, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            val req = vm.requests
            if (req is Load.Ok && req.v.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.requests_title)) }
                items(req.v) { u ->
                    Row(Modifier.fillMaxWidth().padding(16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clickable { onUser(u.id) }, verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(u.avatar, null, Modifier.size(44.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            Spacer(Modifier.width(12.dp))
                            Text(u.display, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Button({ act(u.id, "accept-friend-request") { vm.loadRequests(); vm.loadFriends() } }) { Text(stringResource(R.string.accept)) }
                        TextButton({ act(u.id, "decline-friend-request") { vm.loadRequests() } }) { Text(stringResource(R.string.decline)) }
                    }
                }
            }
            when (val r = results) {
                Load.Idle -> {}
                Load.Busy -> item { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() } }
                is Load.Err -> item { ErrorBox(r.msg, ::search) }
                is Load.Ok -> items(r.v) { u ->
                    Row(Modifier.fillMaxWidth().padding(16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clickable { onUser(u.id) }, verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(u.avatar, null, Modifier.size(44.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(u.display, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("@${u.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (u.id == vm.account?.id) {}
                        else if (u.id in sent) Text(stringResource(R.string.request_sent), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else FilledTonalButton({ act(u.id, "request-friendship") { sent += u.id } }) { Text(stringResource(R.string.add)) }
                    }
                }
            }
        }
    }
}

@Composable
fun NewChatScreen(vm: AppVm, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (vm.friends is Load.Idle) vm.loadFriends() }
    DetailScaffold(stringResource(R.string.new_chat), onBack) { m ->
        if (vm.account == null) { Box(m) { NeedLogin { vm.stack.add(Screen.LoginS) } }; return@DetailScaffold }
        Column(m.fillMaxSize()) {
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            when (val f = vm.friends) {
                Load.Idle, Load.Busy -> Centered { CircularProgressIndicator() }
                is Load.Err -> ErrorBox(f.msg, vm::loadFriends)
                is Load.Ok -> LazyColumn(Modifier.fillMaxSize()) {
                    items(f.v) { fr ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val c = vm.cookie() ?: return@clickable
                                scope.launch {
                                    try {
                                        val id = Api.newConversation(fr.user.id, c)
                                        vm.loadConvos()
                                        vm.stack.removeAt(vm.stack.lastIndex)
                                        vm.stack.add(Screen.ChatS(Convo(id, fr.user.display, false, fr.user.avatar)))
                                    } catch (e: Exception) { error = e.message }
                                }
                            }.padding(16.dp, 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(fr.user.avatar, null, Modifier.size(48.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            Spacer(Modifier.width(14.dp))
                            Text(fr.user.display, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- "Me" tab ----------------

@Composable
fun MeTab(vm: AppVm, modifier: Modifier, onLogin: () -> Unit, onProfile: () -> Unit) {
    val ctx = LocalContext.current
    val me = vm.account
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (me == null) {
            NeedLoginInline(onLogin)
        } else {
            Panel {
                Row(Modifier.clickable(onClick = onProfile), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(me.avatar, null, Modifier.size(72.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(me.display, style = MaterialTheme.typography.titleLarge)
                        Text("@${me.name}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        vm.balance?.let { Text(stringResource(R.string.robux_fmt, compact(it)), color = RobloxGreen) }
                    }
                }
            }
            Panel {
                Text(stringResource(R.string.me_web_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton({ openWeb(ctx, "https://www.roblox.com/catalog") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.me_market)) }
                FilledTonalButton({ openWeb(ctx, "https://www.roblox.com/my/avatar") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.me_avatar)) }
            }
        }
    }
}

@Composable
private fun NeedLoginInline(onLogin: () -> Unit) = Panel {
    Text(stringResource(R.string.login_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onLogin) { Text(stringResource(R.string.login)) }
}

// ---------------- settings ----------------

@Composable
fun SettingsScreen(vm: AppVm, onBack: () -> Unit, onDocs: () -> Unit) {
    val cfg = LocalCfg.current
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf(R.string.set_account, R.string.set_messages, R.string.set_custom, R.string.set_bootstrapper)
    DetailScaffold(stringResource(R.string.tab_settings), onBack) { m ->
        Column(m.fillMaxSize()) {
            ScrollableTabRow(tab, edgePadding = 8.dp) {
                titles.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(stringResource(t)) }) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> AccountPage(vm)
                    1 -> MessagesPage()
                    2 -> CustomPage(cfg, onDocs)
                    else -> BootstrapperPage()
                }
            }
        }
    }
}

@Composable
private fun AccountPage(vm: AppVm) {
    val ctx = LocalContext.current
    val me = vm.account
    if (me == null) { NeedLoginInline { vm.stack.add(Screen.LoginS) }; return }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(me.avatar, null, Modifier.size(64.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(me.display, style = MaterialTheme.typography.titleLarge)
                Text("@${me.name}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.account_id_fmt, me.id), style = MaterialTheme.typography.bodyMedium)
        vm.balance?.let { Text(stringResource(R.string.robux_fmt, compact(it)), color = RobloxGreen) }
    }
    FilledTonalButton({ vm.stack.add(Screen.UserS(me.id)) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.my_profile)) }
    FilledTonalButton({ openWeb(ctx, "https://www.roblox.com/my/account") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.open_account_web)) }
    OutlinedButton({ vm.logout() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.logout)) }
}

@Composable
private fun MessagesPage() {
    val ctx = LocalContext.current
    Panel {
        Text(stringResource(R.string.messages_note), style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton({ openWeb(ctx, "https://www.roblox.com/my/account#!/notifications") }) { Text(stringResource(R.string.open_account_web)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomPage(cfg: Cfg, onDocs: () -> Unit) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    Panel {
        Text(stringResource(R.string.theme_pick), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (BUILTIN_THEMES + cfg.customThemes).forEach { t ->
                FilterChip(cfg.themeId == t.id, { cfg.themeId = t.id; cfg.save() }, { Text(t.name) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ clip.setText(AnnotatedString(themeToJson(cfg.theme()))); copied = true }) {
                Text(stringResource(if (copied) R.string.theme_copied else R.string.theme_copy))
            }
            if (cfg.customThemes.any { it.id == cfg.themeId }) TextButton({
                cfg.customThemes.removeAll { it.id == cfg.themeId }; cfg.themeId = "roblox"; cfg.save()
            }) { Text(stringResource(R.string.theme_delete)) }
        }
    }
    Panel {
        Text(stringResource(R.string.theme_import), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(importText, { importText = it; importError = false }, Modifier.fillMaxWidth(), minLines = 4, maxLines = 10,
            placeholder = { Text(stringResource(R.string.theme_import_hint)) }, isError = importError,
            supportingText = if (importError) ({ Text(stringResource(R.string.theme_invalid)) }) else null)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                val t = parseTheme(importText)
                if (t == null) importError = true
                else {
                    cfg.customThemes.removeAll { it.id == t.id }; cfg.customThemes += t; cfg.themeId = t.id; cfg.save(); importText = ""
                }
            }) { Text(stringResource(R.string.theme_import_btn)) }
            OutlinedButton(onDocs) { Text(stringResource(R.string.theme_docs)) }
        }
    }
    Panel {
        Text(stringResource(R.string.text_size) + ": ${(cfg.textScale * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
        Slider(cfg.textScale, { cfg.textScale = Math.round(it * 20) / 20f }, valueRange = 0.8f..1.5f, steps = 13, onValueChangeFinished = { cfg.save() })
        Text(stringResource(R.string.drawer_opacity) + ": ${(cfg.drawerAlpha * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
        Slider(cfg.drawerAlpha, { cfg.drawerAlpha = Math.round(it * 20) / 20f }, valueRange = 0.3f..1f, steps = 13, onValueChangeFinished = { cfg.save() })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.haptics), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(cfg.haptics, { cfg.haptics = it; cfg.save() })
        }
        if (dorfikstrapInstalled(ctx)) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.via_dorfikstrap), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.via_dorfikstrap_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(cfg.viaDorfikstrap, { cfg.viaDorfikstrap = it; cfg.save() })
        }
    }
    OrderPanel(stringResource(R.string.bottom_bar), cfg.navOrder, cfg.navHidden, 1, cfg) { id ->
        stringResource(when (id) { "home" -> R.string.tab_home; "chat" -> R.string.tab_chat; else -> R.string.tab_me })
    }
    OrderPanel(stringResource(R.string.drawer_menu), cfg.drawerOrder, cfg.drawerHidden, 0, cfg) { id -> stringResource(drawerLabel(id)) }
    OutlinedButton({ cfg.resetLayout() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.layout_reset)) }
}

fun drawerLabel(id: String): Int = when (id) {
    "favorites" -> R.string.drawer_favorites
    "continue" -> R.string.continue_title
    "friends" -> R.string.tab_friends
    "communities" -> R.string.drawer_communities
    "inventory" -> R.string.drawer_inventory
    "trades" -> R.string.drawer_trades
    "messages" -> R.string.drawer_messages
    "settings" -> R.string.tab_settings
    "help" -> R.string.drawer_help
    "learn" -> R.string.drawer_learn
    "create" -> R.string.drawer_create
    "switch" -> R.string.drawer_switch
    else -> R.string.logout
}

/** Reorder (up/down) and show/hide a list of buttons. */
@Composable
private fun OrderPanel(title: String, order: MutableList<String>, hidden: MutableList<String>, minVisible: Int, cfg: Cfg, label: @Composable (String) -> String) {
    Panel {
        Text(title, style = MaterialTheme.typography.titleMedium)
        order.toList().forEachIndexed { i, id ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label(id), Modifier.weight(1f))
                IconButton({ if (i > 0) { order.removeAt(i); order.add(i - 1, id); cfg.save() } }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, null) }
                IconButton({ if (i < order.lastIndex) { order.removeAt(i); order.add(i + 1, id); cfg.save() } }, enabled = i < order.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, null) }
                Switch(id !in hidden, { on ->
                    if (on) hidden.remove(id)
                    else if (order.count { it !in hidden } > minVisible) hidden.add(id)
                    cfg.save()
                })
            }
        }
    }
}

@Composable
private fun BootstrapperPage() {
    val ctx = LocalContext.current
    Panel {
        Text(stringResource(R.string.bootstrapper_note), style = MaterialTheme.typography.bodyMedium)
        if (dorfikstrapInstalled(ctx)) {
            Button({
                ctx.packageManager.getLaunchIntentForPackage(DORFIKSTRAP)?.let { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text(stringResource(R.string.bootstrapper_open)) }
        } else {
            Button({ openWeb(ctx, "https://github.com/dorfandiysoftware-glitch/dorfikstrap/releases") }) { Text(stringResource(R.string.bootstrapper_get)) }
        }
    }
}
