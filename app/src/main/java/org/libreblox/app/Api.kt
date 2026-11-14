package org.libreblox.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

data class Presence(val type: Int, val location: String, val placeId: Long, val gameId: String)
data class Friend(val user: User, val presence: Presence?)
data class Convo(val id: Long, val title: String, val unread: Boolean, val avatar: String?)
data class Msg(val id: String, val senderId: Long, val text: String, val sent: String)

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

    private var csrf: String? = null

    private suspend fun request(url: String, method: String = "GET", cookie: String? = null, body: String? = null): String =
        withContext(Dispatchers.IO) {
            for (attempt in 0..1) {
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = method
                    c.connectTimeout = 15000
                    c.readTimeout = 20000
                    c.setRequestProperty("Accept", "application/json")
                    c.setRequestProperty("User-Agent", "LibreBlox/0.3 (Android)")
                    if (cookie != null) c.setRequestProperty("Cookie", ".ROBLOSECURITY=$cookie")
                    if (method != "GET") csrf?.let { c.setRequestProperty("x-csrf-token", it) }
                    if (body != null) {
                        c.doOutput = true
                        c.setRequestProperty("Content-Type", "application/json")
                        c.outputStream.use { it.write(body.toByteArray()) }
                    }
                    val code = c.responseCode
                    if (code == 403 && method != "GET" && attempt == 0) {
                        val t = c.getHeaderField("x-csrf-token")
                        if (t != null) { csrf = t; continue }
                    }
                    if (code !in 200..299) throw ApiException(code, url)
                    return@withContext c.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    c.disconnect()
                }
            }
            throw ApiException(403, url)
        }

    private suspend fun get(url: String, cookie: String? = null): JSONObject = JSONObject(request(url, "GET", cookie))
    private suspend fun getArr(url: String, cookie: String?): JSONArray = JSONArray(request(url, "GET", cookie))

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

    // ---------------- account: friends, favorites, chat ----------------

    suspend fun presence(ids: List<Long>, cookie: String): Map<Long, Presence> {
        if (ids.isEmpty()) return emptyMap()
        val body = JSONObject().put("userIds", JSONArray(ids.take(200))).toString()
        val j = JSONObject(request("https://presence.roblox.com/v1/presence/users", "POST", cookie, body))
        return j.optJSONArray("userPresences").objects().associate {
            it.optLong("userId") to Presence(it.optInt("userPresenceType"), it.optString("lastLocation"), it.optLong("rootPlaceId").takeIf { p -> p != 0L } ?: it.optLong("placeId"), it.optString("gameId"))
        }
    }

    suspend fun friends(myId: Long, cookie: String): List<Friend> {
        val users = get("https://friends.roblox.com/v1/users/$myId/friends", cookie).optJSONArray("data").objects().map {
            User(it.optLong("id"), it.optString("name"), it.optString("displayName").ifEmpty { it.optString("name") }, it.optBoolean("hasVerifiedBadge"))
        }.withAvatars()
        val pres = runCatching { presence(users.map { it.id }, cookie) }.getOrDefault(emptyMap())
        return users.map { Friend(it, pres[it.id]) }.sortedWith(compareByDescending<Friend> { it.presence?.type ?: 0 }.thenBy { it.user.display.lowercase() })
    }

    suspend fun favorites(userId: Long): List<Game> =
        get("https://games.roblox.com/v2/users/$userId/favorite/games?limit=30&sortOrder=Desc").optJSONArray("data").objects().map {
            Game(it.optLong("id"), it.optJSONObject("rootPlace")?.optLong("id") ?: 0L, it.optString("name"))
        }.filter { it.universeId != 0L }.withIcons()

    suspend fun isFavorite(universeId: Long, cookie: String): Boolean =
        get("https://games.roblox.com/v1/games/$universeId/favorites", cookie).optBoolean("isFavorited")

    suspend fun setFavorite(universeId: Long, value: Boolean, cookie: String) {
        request("https://games.roblox.com/v1/games/$universeId/favorites", "POST", cookie, JSONObject().put("isFavorited", value).toString())
    }

    suspend fun conversations(myId: Long, cookie: String): List<Convo> {
        val arr = getArr("https://chat.roblox.com/v2/get-user-conversations?pageNumber=1&pageSize=30", cookie).objects()
        val firstOthers = arr.map { c ->
            c.optJSONArray("participants").objects().firstOrNull { it.optLong("targetId") != myId }?.optLong("targetId")
        }
        val avatars = headshots(firstOthers.filterNotNull().distinct().take(100))
        return arr.mapIndexed { idx, c ->
            val others = c.optJSONArray("participants").objects().filter { it.optLong("targetId") != myId }.map { it.optString("name") }
            val title = c.optJSONObject("conversationTitle")?.optString("titleForViewer").orEmpty().ifEmpty { c.optString("title") }
                .ifEmpty { others.joinToString(", ") }
            Convo(c.optLong("id"), title, c.optBoolean("hasUnreadMessages"), firstOthers[idx]?.let { avatars[it] })
        }
    }

    suspend fun messages(conversationId: Long, cookie: String): List<Msg> =
        getArr("https://chat.roblox.com/v2/get-messages?conversationId=$conversationId&pageSize=40", cookie).objects().map {
            Msg(it.optString("id"), it.optLong("senderTargetId"), it.optString("content"), it.optString("sent"))
        }.reversed()

    suspend fun sendMessage(conversationId: Long, text: String, cookie: String) {
        request("https://chat.roblox.com/v2/send-message", "POST", cookie,
            JSONObject().put("conversationId", conversationId).put("message", text).toString())
    }
}
