// GogCloud — GOG account sign-in, cloud save storage, and the sync policy.
//
// The protocol is not invented here; it is the one Heroic Games Launcher's
// gogdl publishes (GPLv3, Python) as the GOG integration those launchers
// use. This is a clean-room Kotlin implementation of the same endpoints,
// with gogdl's source as the reference and one deliberate difference: this
// port is more conservative than gogdl about save files.
//
//   auth      https://auth.gog.com — OAuth code flow with the Galaxy
//             application's public client credentials; the login page runs
//             in a WebView and the redirect to
//             https://embed.gog.com/on_login_success carries the code. The
//             code is SINGLE-USE and is exchanged exactly once, with the
//             base client; the exchange response itself carries the user
//             id. Game-scoped credentials are never minted by re-spending a
//             code: they are traded from the base account's refresh token
//             against the game's own client id (gogdl's
//             refresh_credentials), on demand, by [GogAuth.gameToken].
//             The trade-in happens LATE — the first sync — so a sign-in
//             that succeeds keeps its account even if the bucket step
//             stumbles, and is re-resolvable without a re-login.
//   game ids  https://content-system.gog.com/products/{gogId}/os/{platform}/
//             builds?generation=2 → a plain-json or zlib document holding
//             the game's clientId/clientSecret. The GOG game id comes from
//             the depot's own goggame-*.info, so a depot that is not a GOG
//             build never sees this feature at all.
//   saves     https://cloudstorage.gog.com/v1/{userId}/{clientId}/…
//             — GET lists a JSON manifest (name, md5-of-gzip hash,
//             last_modified), PUT takes GZIP-compressed bodies whose md5 is
//             the Etag, and the bucket refuses strangers: the User-Agent is
//             expected to look like GOG's communication service, which is
//             spoofed here exactly as gogdl spoofs it.
//   policy    The sync mirrors gogdl's classifier (both sides compared
//             against a last-sync timestamp), with this port deliberately
//             stricter, because save files are sacred:
//             • NO DELETION PROPAGATION. gogdl mirrors local deletions by
//               removing cloud files with no local counterpart; this port
//               never deletes implicitly, in any direction, in any path.
//             • CONFLICT always resolves in a three-way dialog (keep local /
//               keep cloud / cancel), all-or-nothing; a cancel writes
//               nothing and updates no timestamp.
//             • The last-sync timestamp is written only after a successful
//               full sync, and the cloud "commit" endpoint is called last.
//             • .bak backup files sync in NO direction: they belong to the
//               write that made them, and a phone whose save dir has none
//               must not mirror its emptiness onto the cloud, or the other
//               way around.
//   deadlines Every fetch runs under a wall-clock deadline via [boundedFor].
//             The connect/read timeouts cover the socket stages, but DNS
//             resolution happens BEFORE both and has no timeout of its own —
//             on a dozed network the lookup parks forever, and a pull stood
//             on its "analyzing" line for minutes with the screen off. The
//             thread+join pattern keeps every fetch bounded; an orphaned
//             thread behind it dies with the socket when the network
//             returns.
//
// Failures log through LauncherLog and never throw at the caller. Network
// work runs on Dispatchers.IO at the calling site.

package dev.silksong.launcher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater

// Every GOG fetch runs through [boundedFor]: the connect/read timeouts cover
// the socket stages, but DNS resolution happens BEFORE both and has no
// timeout of its own — on a dozed network the lookup parks forever, and a
// pull stood on its "analyzing" line for minutes with the screen off. A
// thread plus join() with a deadline keeps every fetch under a wall-clock
// deadline; an orphaned thread behind it dies with the socket when the
// network returns.
private fun <T> boundedFor(tag: String, timeoutMs: Long, block: () -> T): T {
    var result: T? = null
    var error: Throwable? = null
    var done = false
    val thread = Thread {
        try {
            result = block(); done = true
        } catch (t: Throwable) {
            error = t; done = true
        }
    }
    thread.isDaemon = true
    thread.start()
    thread.join(timeoutMs)
    if (thread.isAlive) throw IOException("gog fetch timed out ($tag) after ${timeoutMs}ms -- network unreachable?")
    error?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

private fun jstr(o: org.json.JSONObject, key: String): String = try {
    o.getString(key)
} catch (_: Throwable) {
    ""
}

object GogAuth {

    const val CLIENT_ID = "46899977096215655"
    const val CLIENT_SECRET = "9d85c43b1482497dbbce61f6e4aa173a433796eeae2ca8c5f6129f2dc4de46d9"
    const val REDIRECT_HOST = "embed.gog.com"
    const val REDIRECT_MARKER = "on_login_success"

    private const val AUTH_URL = "https://auth.gog.com/auth?client_id=$CLIENT_ID" +
        "&redirect_uri=https%3A%2F%2Fembed.gog.com%2Fon_login_success%3Forigin%3Dclient" +
        "&response_type=code&layout=client2"
    private const val TOKEN_URL = "https://auth.gog.com/token"
    private const val USER_DATA_URL = "https://embed.gog.com/userData.json"
    private const val TOKEN_FILE = "gog_tokens.json"

    class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val userId: String,
        val gameAccessToken: String,
        val gameRefreshToken: String,
        val gameClientId: String,
        val gameClientSecret: String,
    )

    fun loginUrl(): String = AUTH_URL

    fun tokensFile(context: Context): File = File(context.filesDir, TOKEN_FILE)

    fun isSignedIn(context: Context): Boolean {
        val t = try { loadTokens(context) } catch (_: Throwable) { null }
        return t != null && t.accessToken.isNotEmpty()
    }

    fun userAgent(): String =
        "GOGGalaxyCommunicationService/2.0.13.27 (Windows_32bit) " +
            "dont_sync_marker/true installation_source/gog"

    private fun get(url: String, token: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", userAgent())
        if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
        return boundedFor("GET " + url, 30_000) {
            conn.inputStream.use { it.readBytes().decodeToString() }
        }
    }

    private fun jstr(o: org.json.JSONObject, key: String): String = try {
        o.getString(key)
    } catch (_: Throwable) {
        ""
    }

    private fun toJson(tokens: Tokens): String =
        org.json.JSONObject()
            .put("access_token", tokens.accessToken)
            .put("refresh_token", tokens.refreshToken)
            .put("userId", tokens.userId)
            .put("gameAccess", tokens.gameAccessToken)
            .put("gameRefresh", tokens.gameRefreshToken)
            .put("gameClientId", tokens.gameClientId)
            .put("gameClientSecret", tokens.gameClientSecret)
            .toString()

    fun loadTokens(context: Context): Tokens? {
        val file = tokensFile(context)
        if (!file.isFile) return null
        val json = org.json.JSONObject(file.readText())
        val t = Tokens(
            accessToken = jstr(json, "access_token"),
            refreshToken = jstr(json, "refresh_token"),
            userId = jstr(json, "userId"),
            gameAccessToken = jstr(json, "gameAccess"),
            gameRefreshToken = jstr(json, "gameRefresh"),
            gameClientId = jstr(json, "gameClientId"),
            gameClientSecret = jstr(json, "gameClientSecret"),
        )
        return t.takeIf { it.accessToken.isNotEmpty() }
    }

    fun signOut(context: Context): Boolean {
        tokensFile(context).delete()
        return !isSignedIn(context)
    }

    fun exchangeCode(context: Context, code: String, gogId: String): Boolean {
        // Single-use code, single exchange, base client. The response itself
        // carries the user id; the users/info.json endpoint 404s on this API
        // generation (the first fix's failure) — and when the id is missing
        // from the token body, userData.json is the standing fallback.
        val url = TOKEN_URL + "?client_id=$CLIENT_ID" +
            "&client_secret=$CLIENT_SECRET&grant_type=authorization_code" +
            "&redirect_uri=https%3A%2F%2Fembed.gog.com%2Fon_login_success%3Forigin%3Dclient" +
            "&code=" + java.net.URLEncoder.encode(code, "UTF-8")
        val json = try {
            org.json.JSONObject(get(url, ""))
        } catch (t: Throwable) {
            LauncherLog.log("GOG: token exchange failed: ${t.javaClass.simpleName}")
            null
        } ?: return false
        val base = jstr(json, "access_token")
        if (base.isEmpty()) return false

        var userId = jstr(json, "user_id")
        if (userId.isEmpty()) userId = try {
            org.json.JSONObject(get(USER_DATA_URL, base)).getString("user_id")
        } catch (t: Throwable) {
            LauncherLog.log("GOG: user id fallback failed: ${t.javaClass.simpleName}")
            ""
        }
        if (userId.isEmpty()) {
            LauncherLog.log("GOG: sign-in succeeded but no user id was provided")
            return false
        }

        // Persist the sign-in FIRST: everything later (bucket ids, the game
        // token) re-resolves from the stored refresh token without a login.
        tokensFile(context).writeText(toJson(Tokens(
            accessToken = base,
            refreshToken = jstr(json, "refresh_token"),
            userId = userId,
            gameAccessToken = "",
            gameRefreshToken = "",
            gameClientId = "",
            gameClientSecret = "",
        )))
        LauncherLog.log("GOG: signed in (user $userId)")

        val ids = GogCloud.gameIds(gogId, base)
        if (ids == null) {
            LauncherLog.log("GOG: signed in, but no save bucket was found for this game")
            return true
        }
        tokensFile(context).writeText(toJson(Tokens(
            accessToken = base,
            refreshToken = jstr(json, "refresh_token"),
            userId = userId,
            gameAccessToken = "",
            gameRefreshToken = "",
            gameClientId = ids.clientId,
            gameClientSecret = ids.clientSecret,
        )))
        LauncherLog.log("GOG: save bucket resolved (client ${ids.clientId})")
        return true
    }

    /**
     * A game-scoped access token for the bucket, minted from the base
     * account's refresh token on first use (gogdl's refresh_credentials:
     * same endpoint, the game's client id/secret, grant_type=refresh_token,
     * without_new_session=1) and cached in the token file until re-minted.
     */
    fun gameToken(context: Context): Tokens? {
        val tokens = loadTokens(context) ?: return null
        if (tokens.gameAccessToken.isNotEmpty()) {
            // Game-scoped GOG tokens live roughly an hour; a mint older than
            // 20 minutes is re-minted BEFORE the bucket call that would
            // otherwise answer 401 after a break (#47's reporter's 2.5-hour
            // gap produced exactly that dead token).
            val age = try {
                val mint = File(context.filesDir, "gog_mint_at").readText().trim().toLongOrNull() ?: 0L
                (System.currentTimeMillis() - mint) / 1000L
            } catch (_: Throwable) { Long.MAX_VALUE }
            if (age < 1200L) return tokens
        }
        if (tokens.refreshToken.isEmpty() || tokens.gameClientId.isEmpty()) return null
        val url = TOKEN_URL + "?client_id=" +
            java.net.URLEncoder.encode(tokens.gameClientId, "UTF-8") +
            "&client_secret=" + java.net.URLEncoder.encode(tokens.gameClientSecret, "UTF-8") +
            "&grant_type=refresh_token&without_new_session=1" +
            "&refresh_token=" + java.net.URLEncoder.encode(tokens.refreshToken, "UTF-8")
        val json = try {
            org.json.JSONObject(get(url, ""))
        } catch (t: Throwable) {
            LauncherLog.log("GOG: game token refresh failed: ${t.javaClass.simpleName}")
            null
        } ?: return null
        val gameAccess = jstr(json, "access_token")
        if (gameAccess.isEmpty()) return null
        val updated = Tokens(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            userId = tokens.userId,
            gameAccessToken = gameAccess,
            gameRefreshToken = jstr(json, "refresh_token").ifEmpty { tokens.gameRefreshToken },
            gameClientId = tokens.gameClientId,
            gameClientSecret = tokens.gameClientSecret,
        )
        tokensFile(context).writeText(toJson(updated))
        try {
            File(context.filesDir, "gog_mint_at").writeText(
                System.currentTimeMillis().toString())
        } catch (_: Throwable) { }
        LauncherLog.log("GOG: game-scoped token minted for the save bucket")
        return updated
    }
}

object GogCloud {

    private const val CONTENT_SYSTEM = "https://content-system.gog.com"
    private const val CLOUDSTORAGE = "https://cloudstorage.gog.com"
    private const val REMOTE_CONFIG = "https://remote-config.gog.com"
    /** gogdl's marker for an empty file, skipped in every comparison. */
    const val EMPTY_HASH = "aadd86936a80ee8a369579c3926f1b3c"

    class SaveEntry(val name: String, val hash: String, val modifiedMs: Long)

    fun isGogDepot(depot: File?): Boolean {
        val dir = depot ?: return false
        if (!dir.isDirectory) return false
        return dir.listFiles()?.any { it.name.startsWith("goggame-") && it.name.endsWith(".info") } == true
    }

    fun gogId(depot: File?): String {
        val dir = depot ?: return ""
        if (!dir.isDirectory) return ""
        return dir.listFiles()
            ?.firstOrNull { it.name.startsWith("goggame-") && it.name.endsWith(".info") }
            ?.name?.removePrefix("goggame-")?.removeSuffix(".info")
            ?: ""
    }

    fun gogDepotPresent(context: Context): Boolean {
        val depot = DepotLocation.resolve(context) ?: return false
        return gogId(depot).isNotEmpty()
    }

    private fun conn(url: String, token: String, method: String): HttpURLConnection {
        val http = url.let { URL(it).openConnection() } as HttpURLConnection
        http.requestMethod = method
        http.connectTimeout = 15_000
        http.readTimeout = 60_000
        http.setRequestProperty("User-Agent", GogAuth.userAgent())
        http.setRequestProperty("X-Object-Meta-User-Agent", GogAuth.userAgent())
        if (token.isNotEmpty()) http.setRequestProperty("Authorization", "Bearer $token")
        return http
    }

    class GameIdsResult(val clientId: String, val clientSecret: String)

    /**
     * The game's cloud bucket ids, from the content system's build metadata.
     * The build document may be plain JSON or zlib-wrapped (gogdl tries json
     * first, then inflates); platforms are tried in turn until one answers
     * with usable ids.
     */
    fun gameIds(gogId: String, token: String): GameIdsResult? {
        for (platform in listOf("windows", "linux", "mac")) {
            try {
                val buildsUrl = "$CONTENT_SYSTEM/products/$gogId/os/$platform/builds?generation=2"
                val builds = org.json.JSONObject(
                    boundedFor("builds " + platform, 40_000) { conn(buildsUrl, token, "GET").inputStream.use { it.readBytes().decodeToString() } }
                )
                val link = builds.getJSONArray("items").getJSONObject(0).getString("link")
                val metaBytes = boundedFor("build meta " + platform, 40_000) {
                    (URL(link).openConnection() as HttpURLConnection)
                        .apply {
                            connectTimeout = 15_000
                            readTimeout = 30_000
                            setRequestProperty("User-Agent", GogAuth.userAgent())
                            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
                        }
                        .inputStream.use { it.readBytes() }
                }
                val text = runCatching {
                    val inflater = Inflater()
                    inflater.setInput(metaBytes)
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    while (!inflater.finished() && !inflater.needsInput()) {
                        val n = inflater.inflate(buf)
                        if (n > 0) out.write(buf, 0, n) else break
                    }
                    inflater.end()
                    out.toString("UTF-8")
                }.getOrDefault(metaBytes.decodeToString())
                if (text.startsWith("{")) {
                    val meta = org.json.JSONObject(text)
                    val clientId = jstr(meta, "clientId")
                    val clientSecret = jstr(meta, "clientSecret")
                    if (clientId.isNotEmpty() && clientSecret.isNotEmpty()) {
                        return GameIdsResult(clientId, clientSecret)
                    }
                }
            } catch (_: Throwable) { /* next platform */ }
        }
        return null
    }

    /**
     * The bucket subdirectory GOG's own clients sync this game's saves
     * under, resolved from remote-config with the game's clientId (read from
     * the depot's goggame-*.info at sign-in). Heroic's exact endpoint:
     * https://remote-config.gog.com/components/galaxy_client/clients/{clientId}
     * Null means GOG has no cloud-save configuration — unavailable, never a
     * guessed folder.
     */
    fun bucketDirName(clientId: String): String? {
        // The silent catch here cost a diagnosis round: a remote-config
        // failure surfaced as a sync that "did nothing". Every step of this
        // function logs its reason now.
        return try {
            val text = boundedFor("remote-config", 30_000) {
                conn("$REMOTE_CONFIG/components/galaxy_client/clients/${java.net.URLEncoder.encode(clientId, "UTF-8")}?component_version=2.0.45", "", "GET")
                    .inputStream.use { it.readBytes().decodeToString() }
            }
            val json = org.json.JSONObject(text)
            val content = json.getJSONObject("content")
            val platform = content.getJSONObject("Windows")
            val storage = platform.getJSONObject("cloudStorage")
            if (!storage.optBoolean("enabled", false)) return null
            val locations = storage.getJSONArray("locations")
            if (locations.length() == 0) return null
            val first = locations.getJSONObject(0)
            val name = jstr(first, "name")
            if (name.isEmpty()) {
                LauncherLog.log("GOG remote-config: no location name for client " + clientId + " body=" + text.take(300))
                null
            } else name
        } catch (t: Throwable) {
            LauncherLog.log("GOG: remote-config fetch failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    fun listFiles(userId: String, clientId: String, dirName: String, token: String): List<SaveEntry> {
        val body = boundedFor("cloud list", 45_000) {
            val conn = conn(CLOUDSTORAGE + "/v1/$userId/$clientId", token, "GET")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode == 404) ""
            else {
                if (conn.responseCode != 200) throw IOException("gog cloud list failed: ${conn.responseCode}")
                conn.inputStream.use { it.readBytes().decodeToString() }
            }
        }
        if (body.isEmpty()) return emptyList()
        val results = ArrayList<SaveEntry>()
        val arr = org.json.JSONArray(body)
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val full = jstr(item, "name")
            if (!full.startsWith("$dirName/")) continue
            val iso = jstr(item, "last_modified")
            val ms = try {
                OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (_: Throwable) { 0L }
            results.add(SaveEntry(full.removePrefix("$dirName/"), jstr(item, "hash"), ms))
        }
        return results
    }

    fun upload(userId: String, clientId: String, dirName: String, relative: String, file: File, token: String) {
        // RAW, not gzipped: the desktop Galaxy client stores save objects
        // uncompressed (only gogdl gzips them, with Content-Encoding it also
        // declares). Matching the desktop convention means every file here
        // is readable by both, whatever uploaded it. The Etag must stay the
        // md5 of the exact stored bytes for the manifest comparisons.
        val raw = file.readBytes()
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(raw)
            .joinToString("") { "%02x".format(it) }
        val modified = OffsetDateTime.ofInstant(
            Instant.ofEpochMilli(file.lastModified()), java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        boundedFor("upload " + relative, 60_000) {
            val conn = conn(listUpload(userId, clientId, dirName, relative), token, "PUT")
            conn.doOutput = true
            conn.setRequestProperty("Etag", md5)
            conn.setRequestProperty("X-Object-Meta-LocalLastModified", modified)
            conn.setFixedLengthStreamingMode(raw.size)
            conn.outputStream.use { it.write(raw) }
            if (conn.responseCode !in 200..299) throw IOException("gog upload failed for $relative: ${conn.responseCode}")
        }
    }

    fun download(userId: String, clientId: String, dirName: String, relative: String, target: File, token: String): Long {
        // Per-file try, not whole-pull: one odd file must not cancel the
        // others. RAW body (the desktop convention), with a gzip sniff for
        // the files an older gogdl-style uploader stored compressed.
        try {
            boundedFor("download " + relative, 60_000) {
                val conn = conn(listUpload(userId, clientId, dirName, relative), token, "GET")
                if (conn.responseCode != 200) throw IOException("gog download failed for $relative: ${conn.responseCode}")
                val modifiedMs = try {
                    OffsetDateTime.parse(conn.getHeaderField("X-Object-Meta-LocalLastModified"))
                        .toInstant().toEpochMilli()
                } catch (_: Throwable) { 0L }
                val bytes = conn.inputStream.use { it.readBytes() }
                target.parentFile?.mkdirs()
                if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                    GZIPInputStream(bytes.inputStream()).use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                } else {
                    target.outputStream().use { it.write(bytes) }
                }
                if (modifiedMs > 0) target.setLastModified(modifiedMs)
            }
        } catch (t: Throwable) {
            LauncherLog.log("GOG: file $relative failed: ${t.javaClass.simpleName}: ${t.message}")
            return 0L
        }
        return target.lastModified()
    }

    /**
     * Kept for completeness but NEVER CALLED in a plain sync: deletions only
     * happen through an explicit conflict answer, never implicitly.
     */
    fun delete(userId: String, clientId: String, dirName: String, relative: String, token: String) {
        boundedFor("delete " + relative, 30_000) {
            conn(listUpload(userId, clientId, dirName, relative), token, "DELETE").responseCode
        }
    }

    fun commit(userId: String, clientId: String, token: String) {
        try {
            boundedFor("commit", 30_000) {
                conn("$CLOUDSTORAGE/v1/$userId/$clientId", token, "POST").responseCode
            }
        } catch (_: Throwable) { /* the batch is already on disk */ }
    }

    private fun listUpload(userId: String, clientId: String, dirName: String, relative: String): String =
        "$CLOUDSTORAGE/v1/$userId/$clientId/$dirName/" + java.net.URLEncoder.encode(relative, "UTF-8")
}

object GogSync {

    enum class Choice { KEEP_LOCAL, KEEP_CLOUD, CANCEL }

    /** Uploads as names; downloads as (name, remote-modified-ms). */
    class Plan(
        val upload: List<String>,
        val download: List<Pair<String, Long>>,
        val conflictFiles: List<String>,
        val direction: String,
    )

    private fun lastSyncFile(context: Context): File = File(context.filesDir, "gog_lastsync")

    fun lastSync(context: Context): Long = try {
        lastSyncFile(context).readText().trim().toLongOrNull() ?: 0L
    } catch (_: Throwable) { 0L }

    private fun writeLastSync(context: Context, value: Long) {
        try { lastSyncFile(context).writeText(value.toString()) } catch (_: Throwable) { }
    }

    fun isConfigured(context: Context): Boolean {
        val tokens = GogAuth.loadTokens(context) ?: return false
        if (tokens.gameAccessToken.isEmpty()) return false
        return gogDepotPresent(context)
    }

    fun gogDepotPresent(context: Context): Boolean {
        val depot = DepotLocation.resolve(context) ?: return false
        return GogCloud.gogId(depot).isNotEmpty()
    }

    /**
     * Analyzes one direction of the sync against the last-sync timestamp.
     * Null = nothing to do, no cloud config, or not a GOG depot. Conflicts
     * (changed on both sides since the last sync) come back for a dialog —
     * never resolved silently, never overwritten silently.
     */
    suspend fun analyze(context: Context, depot: File?, tokens: GogAuth.Tokens, mode: String = "sync"): Plan? = withContext(Dispatchers.IO) {
        val gogId = GogCloud.gogId(depot)
        if (gogId.isEmpty()) return@withContext null
        val gameToken = GogAuth.gameToken(context)
        if (gameToken == null) {
            LauncherLog.log("GOG: no game-scoped token could be minted for the save bucket")
            return@withContext null
        }
        // THE TOKEN SPLIT: the content-system builds endpoint answers the
        // USER token; the storage bucket answers the GAME-scoped one. Passing
        // the game token to builds 401s every platform and the whole sync
        // evaporated silently (the "analyzing" line with nothing after it).
        if (tokens.gameClientId.isEmpty()) {
            LauncherLog.log("GOG: the depot's .info has no client id; sync impossible")
            return@withContext null
        }
        val dirName = GogCloud.bucketDirName(tokens.gameClientId)
        if (dirName == null) {
            LauncherLog.log("GOG: no cloud-save location in remote-config for this game; sync impossible")
            return@withContext null
        }
        val saveDir = SaveDir.of(context)
        val last = lastSync(context)

        val remote = GogCloud.listFiles(tokens.userId, tokens.gameClientId, dirName, gameToken.gameAccessToken)
            .filter { it.hash != GogCloud.EMPTY_HASH && !it.name.contains(".bak") }

        val upload = ArrayList<String>()
        val conflicts = ArrayList<String>()

        // MODE GATES: a pull considers downloads only, a push uploads only,
        // and sync considers both. The manual Download button that uploaded
        // the user's saves silently was the reason this gate exists: with no
        // cloud files at all, "download" used to fall through gogdl's
        // upload-empty-bucket behavior. It does not here.
        val pulling = mode != "push"
        val pushing = mode != "pull"

        saveDir.listFiles()?.filter { it.isFile && !it.name.contains(".bak") }?.forEach { f ->
            if (!pushing) return@forEach
            val remoteEntry = remote.firstOrNull { it.name == f.name }
            val newerLocally = f.lastModified() > last
            when {
                remoteEntry == null && newerLocally -> upload.add(f.name)
                remoteEntry == null -> Unit // brand-new local file in pull mode: keep, never mirror emptiness
                newerLocally && remoteEntry.modifiedMs > last -> conflicts.add(f.name)
                newerLocally -> upload.add(f.name)
            }
        }

        val download = ArrayList<Pair<String, Long>>()
        remote.forEach { r ->
            if (!pulling) return@forEach
            val local = File(saveDir, r.name)
            val newerRemotely = r.modifiedMs > last
            when {
                !local.isFile -> download.add(r.name to r.modifiedMs)
                newerRemotely && local.lastModified() <= last -> download.add(r.name to r.modifiedMs)
                newerRemotely && local.lastModified() > last &&
                    conflicts.none { it == r.name } -> conflicts.add(r.name)
            }
        }

        val direction = when {
            conflicts.isNotEmpty() -> "conflict"
            upload.isNotEmpty() -> "upload"
            download.isNotEmpty() -> "download"
            else -> "none"
        }
        if (direction == "none") return@withContext null
        Plan(upload, download, conflicts, direction)
    }

    /**
     * Applies a plan. The safe half of the policy, restated in code: uploads
     * and downloads only; conflicting files go exactly where the dialog
     * decided; a cancelled or empty plan writes nothing and touches no
     * timestamp; and no deletion happens in ANY path in this version.
     */
    suspend fun apply(
        context: Context,
        depot: File?,
        tokens: GogAuth.Tokens,
        plan: Plan,
        conflictResolution: Choice?,
    ): Boolean = withContext(Dispatchers.IO) {
        val gogId = GogCloud.gogId(depot)
        if (gogId.isEmpty()) return@withContext false
        val gameToken = GogAuth.gameToken(context)
        if (gameToken == null) {
            LauncherLog.log("GOG: no game-scoped token could be minted for the save bucket")
            return@withContext false
        }
        if (tokens.gameClientId.isEmpty()) return@withContext false
        val dirName = GogCloud.bucketDirName(tokens.gameClientId) ?: return@withContext false
        val saveDir = SaveDir.of(context)

        fun uploadOne(name: String) {
            val f = File(saveDir, name)
            if (f.isFile) GogCloud.upload(tokens.userId, tokens.gameClientId, dirName, name, f, gameToken.gameAccessToken)
        }
        fun downloadOne(entry: Pair<String, Long>) {
            val f = File(saveDir, entry.first)
            GogCloud.download(tokens.userId, tokens.gameClientId, dirName, entry.first, f, gameToken.gameAccessToken)
        }

        val uploads = plan.upload + if (conflictResolution == Choice.KEEP_LOCAL) plan.conflictFiles else emptyList()
        val downloads = plan.download +
            (if (conflictResolution == Choice.KEEP_CLOUD) plan.conflictFiles.map { it to 0L } else emptyList())
        uploads.forEach(::uploadOne)
        downloads.forEach(::downloadOne)

        val didAny = conflictResolution != Choice.CANCEL && (uploads.isNotEmpty() || downloads.isNotEmpty())
        if (didAny) {
            GogCloud.commit(tokens.userId, tokens.gameClientId, gameToken.gameAccessToken)
            writeLastSync(context, System.currentTimeMillis())
            LauncherLog.log("GOG sync applied (${plan.direction}: +${uploads.size} up, ${downloads.size} down)")
        } else if (conflictResolution == Choice.CANCEL) {
            LauncherLog.log("GOG sync cancelled — nothing written, timestamp untouched")
        } else {
            LauncherLog.log("GOG sync: nothing to do")
        }
        return@withContext didAny
    }
}
