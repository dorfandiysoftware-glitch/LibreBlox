package org.libreblox.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Busy : Load<Nothing>
    data class Ok<T>(val v: T) : Load<T>
    data class Err(val msg: String) : Load<Nothing>
}

sealed interface Screen {
    data class GameS(val game: Game) : Screen
    data class UserS(val id: Long) : Screen
    data class ChatS(val convo: Convo) : Screen
    data object LoginS : Screen
}

data class HomeRow(val id: String, val title: String, val games: Load<List<Game>>)

class AppVm(private val app: Application) : AndroidViewModel(app) {
    val stack = mutableStateListOf<Screen>()

    // home
    var rows by mutableStateOf<List<HomeRow>>(emptyList())
    var homeError by mutableStateOf<String?>(null)
    var homeBusy by mutableStateOf(false)

    // search
    var searchPeople by mutableStateOf(false)
    var gameQuery by mutableStateOf("")
    var gameSearch by mutableStateOf<Load<List<Game>>>(Load.Idle)
    var userQuery by mutableStateOf("")
    var userSearch by mutableStateOf<Load<List<User>>>(Load.Idle)

    // account + social
    var account by mutableStateOf<User?>(null)
    var favorites by mutableStateOf<Load<List<Game>>>(Load.Idle)
    var friends by mutableStateOf<Load<List<Friend>>>(Load.Idle)
    var convos by mutableStateOf<Load<List<Convo>>>(Load.Idle)

    init {
        loadHome()
        refreshAccount()
    }

    private fun err(e: Throwable) = Load.Err(e.message ?: e.toString())
    fun cookie(): String? = Session.load(app)

    fun loadHome() {
        homeBusy = true
        homeError = null
        viewModelScope.launch {
            try {
                val sorts = runCatching { Api.sorts() }.getOrDefault(emptyList())
                    .ifEmpty { listOf(Sort("top-playing-now", "Popular")) }.take(6)
                rows = sorts.map { HomeRow(it.id, it.title, Load.Busy) }
                homeBusy = false
                sorts.forEachIndexed { i, s ->
                    launch {
                        val r = try { Load.Ok(Api.sortGames(s.id)) } catch (e: Exception) { err(e) }
                        rows = rows.toMutableList().also { l -> if (i < l.size) l[i] = l[i].copy(games = r) }
                    }
                }
            } catch (e: Exception) {
                homeError = e.message ?: e.toString()
                homeBusy = false
            }
        }
    }

    fun refreshAccount() {
        val cookie = Session.load(app) ?: run { clearSocial(); account = null; return }
        viewModelScope.launch {
            // Keep the previous value on network errors; only a rejected cookie logs the user out.
            try {
                val me = Api.me(cookie)
                if (me == null) { Session.clear(app); clearSocial() }
                account = me
                if (me != null) loadSocial()
            } catch (_: Exception) {}
        }
    }

    private fun clearSocial() {
        favorites = Load.Idle; friends = Load.Idle; convos = Load.Idle
    }

    fun loadSocial() {
        loadFavorites(); loadFriends(); loadConvos()
    }

    fun loadFavorites() {
        val me = account ?: return
        favorites = Load.Busy
        viewModelScope.launch { favorites = try { Load.Ok(Api.favorites(me.id)) } catch (e: Exception) { err(e) } }
    }

    fun loadFriends() {
        val me = account ?: return
        val c = cookie() ?: return
        friends = Load.Busy
        viewModelScope.launch { friends = try { Load.Ok(Api.friends(me.id, c)) } catch (e: Exception) { err(e) } }
    }

    fun loadConvos() {
        val me = account ?: return
        val c = cookie() ?: return
        convos = Load.Busy
        viewModelScope.launch { convos = try { Load.Ok(Api.conversations(me.id, c)) } catch (e: Exception) { err(e) } }
    }

    fun logout() {
        Session.clear(app)
        account = null
        clearSocial()
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        } catch (_: Exception) {}
    }

    fun searchGames() {
        val q = gameQuery.trim()
        if (q.isEmpty()) { gameSearch = Load.Idle; return }
        gameSearch = Load.Busy
        viewModelScope.launch { gameSearch = try { Load.Ok(Api.searchGames(q)) } catch (e: Exception) { err(e) } }
    }

    fun searchUsers() {
        val q = userQuery.trim()
        if (q.isEmpty()) { userSearch = Load.Idle; return }
        userSearch = Load.Busy
        viewModelScope.launch { userSearch = try { Load.Ok(Api.searchUsers(q)) } catch (e: Exception) { err(e) } }
    }
}
