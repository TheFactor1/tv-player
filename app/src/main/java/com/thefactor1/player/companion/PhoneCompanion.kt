package com.thefactor1.player.companion

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** What the phone can see and do while a video plays. The player implements it; calls arrive on the main thread. */
interface CompanionHost {
    /** title, kicker, positionMs, durationMs, playing. */
    fun companionState(): JSONObject
    /** [action] is play, pause, toggle, seek (to [ms]) or skip (by [ms]). */
    fun companionControl(action: String, ms: Long)
}

/** A cast member as the phone shows them, with a full photo URL. */
data class CompanionPerson(val name: String, val character: String?, val photoUrl: String?)

/**
 * The phone companion: a small web server on the TV that serves one page and a JSON API, on the
 * home Wi-Fi only. A phone pairs once with a 4-digit code shown on the TV and keeps its token, so
 * it reconnects on its own after that. It runs while the home screen or the player is open.
 */
object PhoneCompanion {
    const val PORT = 8765

    @Volatile var host: CompanionHost? = null
    @Volatile var transcript: List<Cue> = emptyList()
        set(value) {
            field = value
            transcriptVersion++
        }
    @Volatile private var transcriptVersion = 0
    @Volatile var cast: List<CompanionPerson> = emptyList()

    /**
     * Asks whoever is on screen whether a phone may connect; null while no screen can ask. Called on
     * the main thread; [answer] takes the viewer's choice.
     */
    @Volatile var approver: ((device: String, answer: (Boolean) -> Unit) -> Unit)? = null

    private val requests = PairRequests()
    private var nsd: NsdManager.RegistrationListener? = null

    /** The code a new phone types in; it changes after every pairing and after repeated wrong guesses. */
    @Volatile var code: String = newCode()
        private set

    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private var users = 0
    private var server: ServerSocket? = null
    private var appContext: Context? = null
    private var wrongGuesses = 0
    // Each phone app holds one thread in a long poll, so room for a few phones plus page requests.
    private val workers = Executors.newFixedThreadPool(8) { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "companion").apply { isDaemon = true }
    }

    @Volatile private var transcriptTicket = 0
    /** True once a whole subtitle file filled the transcript; until then it's built live from the screen. */
    @Volatile private var transcriptFromFile = false

    /** Subtitle files the launching app passed with this video: same release, so the best timing. */
    @Volatile var launchSubtitles: List<String> = emptyList()

    /** Why the transcript is (or isn't) there, so the phone can say: waiting, loading, file, none, failed, live. */
    @Volatile private var transcriptStatus = "waiting"

    /** Maps the downloaded file's times onto the video, learned from the lines the TV shows. */
    private val sync = TranscriptSync()

    /** Clears what the phone shows, e.g. when a different video starts. */
    fun reset() {
        transcriptTicket++
        transcriptFromFile = false
        transcriptStatus = "waiting"
        synchronized(sync) { sync.reset() }
        transcript = emptyList()
        cast = emptyList()
    }

    /**
     * Downloads subtitle files for the phone's transcript, trying [urls] in order until one reads;
     * the TV's own subtitles are left alone.
     */
    fun loadTranscript(urls: List<String>) {
        if (urls.isEmpty()) {
            if (!transcriptFromFile && transcript.isEmpty()) transcriptStatus = "none"
            return
        }
        val ticket = ++transcriptTicket
        if (!transcriptFromFile) transcriptStatus = "loading"
        workers.execute {
            for (url in urls.distinct()) {
                if (ticket != transcriptTicket) return@execute
                val text = SubtitleFetch.text(url)
                val cues = text?.let(Srt::parse).orEmpty()
                Log.i(TAG, "Transcript ${url.take(80)}: ${if (text == null) "download failed" else "${cues.size} lines"}")
                if (cues.isEmpty()) continue
                if (ticket == transcriptTicket) {
                    synchronized(sync) { sync.reset() }
                    transcriptFromFile = true
                    transcriptStatus = "file"
                    transcript = cues
                }
                return@execute
            }
            if (ticket == transcriptTicket && !transcriptFromFile) transcriptStatus = if (transcript.isEmpty()) "failed" else "live"
        }
    }

    /** A line the TV just showed; fills the transcript as you watch when no subtitle file could be read. */
    fun liveCue(positionMs: Long, text: String) {
        val clean = text.replace(Regex("""\s+"""), " ").trim()
        if (clean.isEmpty()) return
        if (transcriptFromFile) {
            if (synchronized(sync) { sync.observe(positionMs, clean, transcript) }) transcriptVersion++
            return
        }
        val current = transcript
        if (current.any { it.text == clean && kotlin.math.abs(it.startMs - positionMs) < LIVE_REPEAT_MS }) return
        transcript = (current + Cue(positionMs, positionMs, clean)).sortedBy { it.startMs }
        if (transcriptStatus != "loading") transcriptStatus = "live"
    }

    /** Called from each screen's onStart; the server runs while at least one screen is showing. */
    @Synchronized
    fun start(context: Context) {
        appContext = context.applicationContext
        users++
        if (server != null) return
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        } catch (e: Exception) {
            return
        }
        server = socket
        Thread({ acceptLoop(socket) }, "companion-accept").apply { isDaemon = true }.start()
        advertise(context)
    }

    /** Announces the TV on the home network so the phone app finds it without an address. */
    private fun advertise(context: Context) {
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.i(TAG, "Advertising failed: $error") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = PORT
        }
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsd = listener
        } catch (e: Exception) {
            Log.i(TAG, "Advertising failed: ${e.message}")
        }
    }

    /** Called from each screen's onStop. */
    @Synchronized
    fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        try {
            server?.close()
        } catch (e: Exception) {
        }
        server = null
        nsd?.let { listener ->
            try {
                (appContext?.getSystemService(Context.NSD_SERVICE) as? NsdManager)?.unregisterService(listener)
            } catch (e: Exception) {
            }
        }
        nsd = null
    }

    /** "192.168.50.2:8765", or null when the TV has no home-network address. */
    fun address(): String? {
        val ip = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
        return ip?.let { "$it:$PORT" }
    }

    /** What the TV's QR code opens: the phone page with this pairing code filled in, so it pairs on arrival. */
    fun pairUrl(): String? = address()?.let { "http://$it/#pair=$code" }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                return
            }
            workers.execute { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 5_000
                val request = Http.read(BufferedInputStream(socket.getInputStream())) ?: return
                val response = route(request)
                socket.getOutputStream().apply {
                    write(response.bytes())
                    flush()
                }
            } catch (e: Exception) {
                // A phone that went away mid-request: nothing to do.
            }
        }
    }

    private fun route(request: Http.Request): Http.Response {
        val path = request.path.substringBefore('?')
        if (request.method == "GET" && (path == "/" || path == "/index.html")) return page()
        if (!path.startsWith("/api/")) return Http.Response(404, "text/plain", "Not found".toByteArray())
        if (path == "/api/pair" && request.method == "POST") return pair(request)
        if (path == "/api/hello" && request.method == "POST") return hello(request)
        if (path == "/api/hello" && request.method == "GET") return helloAnswer(request)
        if (!authorised(request.headers["x-token"])) return json(401, JSONObject().put("error", "pair"))
        return when (path) {
            "/api/state" -> json(200, state())
            "/api/wait" -> json(200, waitForChange(query(request, "key")))
            "/api/transcript" -> json(200, transcriptJson())
            "/api/cast" -> json(200, castJson())
            "/api/control" -> control(request)
            else -> Http.Response(404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun page(): Http.Response {
        val context = appContext ?: return Http.Response(503, "text/plain", ByteArray(0))
        val bytes = context.assets.open("companion/index.html").use { it.readBytes() }
        return Http.Response(200, "text/html; charset=utf-8", bytes)
    }

    @Synchronized
    private fun pair(request: Http.Request): Http.Response {
        val guess = try {
            JSONObject(request.body).optString("code")
        } catch (e: Exception) {
            ""
        }
        if (guess != code) {
            if (++wrongGuesses >= MAX_WRONG_GUESSES) {
                code = newCode()
                wrongGuesses = 0
            }
            return json(403, JSONObject().put("error", "code"))
        }
        wrongGuesses = 0
        code = newCode()
        return json(200, JSONObject().put("token", issueToken()))
    }

    private fun issueToken(): String {
        val token = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs()?.let { prefs ->
            val tokens = prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty() + token
            prefs.edit().putStringSet(KEY_TOKENS, tokens.toList().takeLast(MAX_PHONES).toSet()).apply()
        }
        return token
    }

    /** The phone app asks to connect; the TV puts the question on screen. */
    private fun hello(request: Http.Request): Http.Response {
        val device = try {
            JSONObject(request.body).optString("device")
        } catch (e: Exception) {
            ""
        }.take(MAX_DEVICE_NAME).ifBlank { "A phone" }
        val ask = approver ?: return json(503, JSONObject().put("error", "screen"))
        val id = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        if (!requests.open(id)) return json(429, JSONObject().put("error", "busy"))
        main.post { ask(device) { approved -> requests.answer(id, approved, if (approved) issueToken() else null) } }
        return json(200, JSONObject().put("request", id))
    }

    private fun helloAnswer(request: Http.Request): Http.Response {
        val answer = requests.check(query(request, "id").orEmpty())
        val body = JSONObject().put("status", answer.status.name.lowercase())
        answer.token?.let { body.put("token", it) }
        return json(200, body)
    }

    /**
     * Long poll for the phone app: answers as soon as what's playing differs from [since], or after a
     * while regardless, so the phone's notification follows the TV without asking every second.
     */
    private fun waitForChange(since: String?): JSONObject {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (true) {
            val state = state()
            val key = nowPlayingKey(state)
            if (key != since || System.currentTimeMillis() >= deadline) return state.put("key", key)
            Thread.sleep(WAIT_STEP_MS)
        }
    }

    private fun nowPlayingKey(state: JSONObject): String =
        if (state.optBoolean("idle")) "idle" else "${state.optString("title")}|${state.optBoolean("playing")}"

    private fun query(request: Http.Request, name: String): String? =
        request.path.substringAfter('?', "").split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == name }
            ?.let { java.net.URLDecoder.decode(it[1], "UTF-8") }

    private fun authorised(token: String?): Boolean =
        !token.isNullOrEmpty() && prefs()?.getStringSet(KEY_TOKENS, emptySet()).orEmpty().contains(token)

    private fun state(): JSONObject {
        val state = onMain { host?.companionState() } ?: JSONObject().put("idle", true)
        return state.put("transcriptVersion", transcriptVersion).put("transcriptStatus", transcriptStatus).put("castCount", cast.size)
    }

    private fun transcriptJson(): JSONObject {
        val cues = JSONArray()
        val fromFile = transcriptFromFile
        synchronized(sync) {
            transcript.forEach {
                val start = if (fromFile) sync.map(it.startMs) else it.startMs
                val end = if (fromFile) sync.map(it.endMs) else it.endMs
                cues.put(JSONArray().put(start).put(end).put(it.text))
            }
        }
        return JSONObject().put("version", transcriptVersion).put("cues", cues)
    }

    private fun castJson(): JSONObject {
        val people = JSONArray()
        cast.forEach {
            people.put(JSONObject().put("name", it.name).put("character", it.character ?: "").put("photo", it.photoUrl ?: ""))
        }
        return JSONObject().put("cast", people)
    }

    private fun control(request: Http.Request): Http.Response {
        if (request.method != "POST") return json(405, JSONObject())
        val body = try {
            JSONObject(request.body)
        } catch (e: Exception) {
            return json(400, JSONObject())
        }
        val action = body.optString("action")
        if (action !in ACTIONS) return json(400, JSONObject())
        onMain { host?.companionControl(action, body.optLong("ms")) }
        return json(200, state())
    }

    /** Runs [block] on the main thread, where the player lives, and waits briefly for its answer. */
    private fun <T> onMain(block: () -> T): T? {
        val task = FutureTask(block)
        main.post(task)
        return try {
            task.get(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
    }

    private fun json(status: Int, body: JSONObject) =
        Http.Response(status, "application/json; charset=utf-8", body.toString().toByteArray())

    private fun prefs() = appContext?.getSharedPreferences("companion", Context.MODE_PRIVATE)

    private fun newCode() = "%04d".format(SecureRandom().nextInt(10_000))

    private const val KEY_TOKENS = "tokens"
    private const val MAX_PHONES = 8
    private const val MAX_WRONG_GUESSES = 5
    private const val LIVE_REPEAT_MS = 10_000L
    private const val TAG = "PhoneCompanion"
    private const val SERVICE_TYPE = "_tvplayer._tcp"
    private const val SERVICE_NAME = "TV Player"
    private const val MAX_DEVICE_NAME = 40
    private const val WAIT_MS = 25_000L
    private const val WAIT_STEP_MS = 600L
    private val ACTIONS = setOf("play", "pause", "toggle", "seek", "skip")
}
