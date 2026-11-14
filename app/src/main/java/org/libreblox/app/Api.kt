package org.libreblox.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class ApiException(val code: Int, val url: String) : Exception("HTTP $code\n$url")

data class Game(
    val universeId: Long,
    val placeId: Long,
    val name: String,
    val players: Long = 0,
    val up: Long = 0,
    val down: Long = 0,
    val icon: String? = null,
)

data class GameDetails(
    val game: Game,
    val description: String,
    val creator: String,
    val visits: Long,
    val favorites: Long,
    val maxPlayers: Int,
    val genre: String,
    val shots: List<String>,
)

data class Sort(val id: String, val title: String)

data class User(val id: Long, val name: String, val display: String, val verified: Boolean = false, val avatar: String? = null)

data class UserDetails(
    val user: User,
    val description: String,
    val created: String,
    val banned: Boolean,
    val friends: Long,
    val followers: Long,
    val following: Long,
    val bigAvatar: String?,
    val friendList: List<User>,
    val games: List<Game>,
)

/**
 * Thin client for Roblox's public web endpoints. They are unofficial and may change without notice,
 * so every field is read defensively.
 */
object Api {
    private val session = UUID.randomUUID().toString()

    private suspend fun get(url: String, cookie: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "LibreBlox/0.1 (Android)")
            if (cookie != null) c.setRequestProperty("Cookie", ".ROBLOSECURITY=$cookie")
            val code = c.responseCode
            if (code !in 200..299) throw ApiException(code, url)
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    /** The account the session cookie belongs to, or null when it is no longer valid. */
    suspend fun me(cookie: String): User? = try {
        val j = get("https://users.roblox.com/v1/users/authenticated", cookie)
        val id = j.optLong("id")
        if (id == 0L) null else User(id, j.optString("name"), j.optString("displayName").ifEmpty { j.optString("name") }, avatar = headshots(listOf(id))[id])
    } catch (e: ApiException) {
        if (e.code == 401 || e.code == 403) null else throw e
    }

    // ---------------- thumbnails ----------------

    private suspend fun gameIcons(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val j = runCatching {
            get("https://thumbnails.roblox.com/v1/games/icons?universeIds=${ids.joinToString(",")}&returnPolicy=PlaceHolder&size=256x256&format=Png&isCircular=false")
        }.getOrNull() ?: return emptyMap()
        return j.optJSONArray("data").objects().mapNotNull {
            val u = it.optString("imageUrl")
            if (u.isNotEmpty()) it.optLong("targetId") to u else null
        }.toMap()
    }

    private suspend fun headshots(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val j = runCatching {
            get("https://thumbnails.roblox.com/v1/users/avatar-headshot?userIds=${ids.joinToString(",")}&size=150x150&format=Png&isCircular=false")
        }.getOrNull() ?: return emptyMap()
        return j.optJSONArray("data").objects().mapNotNull {
            val u = it.optString("imageUrl")
            if (u.isNotEmpty()) it.optLong("targetId") to u else null
        }.toMap()
    }

    private suspend fun List<Game>.withIcons(): List<Game> {
        val icons = gameIcons(map { it.universeId }.distinct().take(100))
        return map { it.copy(icon = icons[it.universeId]) }
    }

    private suspend fun List<User>.withAvatars(): List<User> {
        val a = headshots(map { it.id }.distinct().take(100))
        return map { it.copy(avatar = a[it.id]) }
    }

    private fun gameFrom(o: JSONObject) = Game(
        universeId = o.optLong("universeId"),
        placeId = o.optLong("rootPlaceId"),
        name = o.optString("name"),
        players = o.optLong("playerCount"),
        up = o.optLong("totalUpVotes"),
        down = o.optLong("totalDownVotes"),
    )

    // ---------------- discover ----------------

    suspend fun sorts(): List<Sort> {
        val j = get("https://apis.roblox.com/explore-api/v1/get-sorts?sessionId=$session&device=phone&country=all")
        val list = j.optJSONArray("sorts").objects()
            .filter { it.optString("contentType", "Games").equals("Games", true) && it.optString("sortId").isNotEmpty() }
            .map { Sort(it.optString("sortId"), it.optString("sortDisplayName").ifEmpty { it.optString("sortId") }) }
        return list
    }

    suspend fun sortGames(sortId: String): List<Game> {
        val j = get("https://apis.roblox.com/explore-api/v1/get-sort-content?sessionId=$session&device=phone&country=all&sortId=${enc(sortId)}")
        return j.optJSONArray("games").objects().map(::gameFrom).filter { it.universeId != 0L }.withIcons()
    }

    // ---------------- search ----------------

    suspend fun searchGames(query: String): List<Game> {
        val j = get("https://apis.roblox.com/search-api/omni-search?searchQuery=${enc(query)}&sessionId=$session&pageType=all")
        val out = mutableListOf<Game>()
        for (g in j.optJSONArray("searchResults").objects()) {
            if (!g.optString("contentGroupType").equals("Game", true)) continue
            out += g.optJSONArray("contents").objects().map(::gameFrom)
        }
        return out.filter { it.universeId != 0L }.withIcons()
    }

    suspend fun searchUsers(query: String): List<User> {
        val j = get("https://users.roblox.com/v1/users/search?keyword=${enc(query)}&limit=25")
        return j.optJSONArray("data").objects().map {
            User(it.optLong("id"), it.optString("name"), it.optString("displayName").ifEmpty { it.optString("name") }, it.optBoolean("hasVerifiedBadge"))
        }.withAvatars()
    }

    // ---------------- details ----------------

    suspend fun gameDetails(base: Game): GameDetails {
        val id = base.universeId
        val j = get("https://games.roblox.com/v1/games?universeIds=$id").optJSONArray("data").objects().firstOrNull()
            ?: throw ApiException(404, "universe $id")
        val votes = runCatching { get("https://games.roblox.com/v1/games/votes?universeIds=$id").optJSONArray("data").objects().firstOrNull() }.getOrNull()
        val icon = base.icon ?: gameIcons(listOf(id))[id]
        val shots = runCatching {
            get("https://thumbnails.roblox.com/v1/games/multiget/thumbnails?universeIds=$id&countPerUniverse=8&size=768x432&format=Png&isCircular=false")
                .optJSONArray("data").objects().firstOrNull()?.optJSONArray("thumbnails").objects()
                .mapNotNull { it.optString("imageUrl").ifEmpty { null } }
        }.getOrNull().orEmpty()
        val game = Game(
            universeId = id,
            placeId = j.optLong("rootPlaceId").takeIf { it != 0L } ?: base.placeId,
            name = j.optString("name").ifEmpty { base.name },
            players = j.optLong("playing"),
            up = votes?.optLong("upVotes") ?: base.up,
            down = votes?.optLong("downVotes") ?: base.down,
            icon = icon,
        )
        return GameDetails(
            game = game,
            description = j.optString("description"),
            creator = j.optJSONObject("creator")?.optString("name").orEmpty(),
            visits = j.optLong("visits"),
            favorites = j.optLong("favoritedCount"),
            maxPlayers = j.optInt("maxPlayers"),
            genre = j.optString("genre"),
            shots = shots,
        )
    }

    private suspend fun count(url: String): Long = runCatching { get(url).optLong("count") }.getOrDefault(0L)

    suspend fun userDetails(id: Long): UserDetails {
        val j = get("https://users.roblox.com/v1/users/$id")
        val user = User(id, j.optString("name"), j.optString("displayName").ifEmpty { j.optString("name") }, j.optBoolean("hasVerifiedBadge"))
        val big = runCatching {
            get("https://thumbnails.roblox.com/v1/users/avatar?userIds=$id&size=420x420&format=Png&isCircular=false")
                .optJSONArray("data").objects().firstOrNull()?.optString("imageUrl")?.ifEmpty { null }
        }.getOrNull()
        val friends = runCatching {
            get("https://friends.roblox.com/v1/users/$id/friends").optJSONArray("data").objects()
                .map { User(it.optLong("id"), it.optString("name"), it.optString("displayName").ifEmpty { it.optString("name") }, it.optBoolean("hasVerifiedBadge")) }
                .take(50).withAvatars()
        }.getOrDefault(emptyList())
        val games = runCatching {
            get("https://games.roblox.com/v2/users/$id/games?limit=12&sortOrder=Desc").optJSONArray("data").objects().map {
                Game(it.optLong("id"), it.optJSONObject("rootPlace")?.optLong("id") ?: 0L, it.optString("name"))
            }.withIcons()
        }.getOrDefault(emptyList())
        return UserDetails(
            user = user.copy(avatar = big),
            description = j.optString("description"),
            created = j.optString("created").take(10),
            banned = j.optBoolean("isBanned"),
            friends = count("https://friends.roblox.com/v1/users/$id/friends/count"),
            followers = count("https://friends.roblox.com/v1/users/$id/followers/count"),
            following = count("https://friends.roblox.com/v1/users/$id/followings/count"),
            bigAvatar = big,
            friendList = friends,
            games = games,
        )
    }
}
