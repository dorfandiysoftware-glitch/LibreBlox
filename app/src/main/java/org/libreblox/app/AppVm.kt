package org.libreblox.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
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
    data object LoginS : Screen
}

class AppVm(private val app: Application) : AndroidViewModel(app) {
    val stack = mutableStateListOf<Screen>()

    var sorts by mutableStateOf<List<Sort>>(emptyList())
    var sortId by mutableStateOf<String?>(null)
    var games by mutableStateOf<Load<List<Game>>>(Load.Idle)

    var gameQuery by mutableStateOf("")
    var gameSearch by mutableStateOf<Load<List<Game>>>(Load.Idle)

    var userQuery by mutableStateOf("")
    var userSearch by mutableStateOf<Load<List<User>>>(Load.Idle)

    var account by mutableStateOf<User?>(null)

    init {
        loadHome()
        refreshAccount()
    }

    fun refreshAccount() {
        val cookie = Session.load(app) ?: run { account = null; return }
        viewModelScope.launch {
            // Keep the previous value on network errors; only a rejected cookie logs the user out.
            try {
                val me = Api.me(cookie)
                if (me == null) Session.clear(app)
                account = me
            } catch (_: Exception) {}
        }
    }

    fun logout() {
        Session.clear(app)
        account = null
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        } catch (_: Exception) {}
    }

    private fun err(e: Throwable) = Load.Err(e.message ?: e.toString())

    fun loadHome() {
        games = Load.Busy
        viewModelScope.launch {
            try {
                if (sorts.isEmpty()) {
                    sorts = runCatching { Api.sorts() }.getOrDefault(emptyList())
                        .ifEmpty { listOf(Sort("top-playing-now", "Popular")) }
                }
                val id = sortId ?: sorts.first().id
                sortId = id
                games = Load.Ok(Api.sortGames(id))
            } catch (e: Exception) {
                games = err(e)
            }
        }
    }

    fun pickSort(id: String) {
        sortId = id
        loadHome()
    }

    fun searchGames() {
        val q = gameQuery.trim()
        if (q.isEmpty()) { gameSearch = Load.Idle; return }
        gameSearch = Load.Busy
        viewModelScope.launch {
            gameSearch = try { Load.Ok(Api.searchGames(q)) } catch (e: Exception) { err(e) }
        }
    }

    fun searchUsers() {
        val q = userQuery.trim()
        if (q.isEmpty()) { userSearch = Load.Idle; return }
        userSearch = Load.Busy
        viewModelScope.launch {
            userSearch = try { Load.Ok(Api.searchUsers(q)) } catch (e: Exception) { err(e) }
        }
    }
}
