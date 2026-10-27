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

/** Whatever screen can put the invisible pairing code over the picture. Calls arrive on the main thread. */
interface BeaconScreen {
    /** Shows [cells] for [seconds]; the display's refresh rate, negative when too slow to hide it, or null with no screen. */
    fun showBeacon(cells: BooleanArray, strength: Int, seconds: Int): Float?
    fun hideBeacon()
    /** A phone just paired by reading the code: a small note, so the room knows. */
    fun phoneConnected(name: String)
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
        set(value) {
            field = value
            castVersion++
        }
    // Counted per change, not per size: two shows with eight people each are still different casts.
    @Volatile private var castVersion = 0

    /**
     * Asks whoever is on screen whether a phone may connect; null while no screen can ask. Called on
     * the main thread; [answer] takes the viewer's choice.
     */
    @Volatile var approver: ((device: String, answer: (Boolean) -> Unit) -> Unit)? = null

    /** Puts the invisible code on screen; set by the screen that's showing. */
    @Volatile var beaconScreen: BeaconScreen? = null
    /** The code the beacon is showing, kept until it stops so the phone can read it across renewals. */
    @Volatile private var beaconCode: Int? = null
    private val beaconGuard = BeaconGuard()

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
        // Pairing by camera starts before the phone has a token: anyone can ask for the code, only what
        // can see the screen can answer it.
        if (path == "/api/beacon") return beacon(request, authorised(request.headers["x-token"]))
        if (path == "/api/beacon/pair" && request.method == "POST") return beaconPair(request)
        if (!authorised(request.headers["x-token"])) return json(401, JSONObject().put("error", "pair"))
        return when (path) {
            "/api/state" -> json(200, state())
            "/api/wait" -> json(200, waitForChange(query(request, "key")))
            "/api/transcript" -> json(200, transcriptJson())
            "/api/cast" -> json(200, castJson())
            "/api/control" -> control(request)
            "/api/unpair" -> unpair(request)
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
        val body = try {
            JSONObject(request.body)
        } catch (e: Exception) {
            JSONObject()
        }
        val guess = body.optString("code")
        if (guess != code) {
            if (++wrongGuesses >= MAX_WRONG_GUESSES) {
                code = newCode()
                wrongGuesses = 0
            }
            return json(403, JSONObject().put("error", "code"))
        }
        wrongGuesses = 0
        code = newCode()
        return json(200, JSONObject().put("token", issueToken(deviceName(body, "A browser"))))
    }

    private fun deviceName(body: JSONObject, fallback: String) =
        body.optString("device").take(MAX_DEVICE_NAME).ifBlank { fallback }

    private fun issueToken(name: String): String {
        val token = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        synchronized(this) {
            savePhones(PairedPhones.add(phones(), PairedPhone(token, name, System.currentTimeMillis())))
        }
        return token
    }

    /** The phones allowed in, oldest first, as the TV's settings list them. */
    @Synchronized
    fun phones(): List<PairedPhone> {
        val prefs = prefs() ?: return emptyList()
        // Tokens kept before phones had names carry over once, as unnamed phones.
        prefs.getStringSet(KEY_TOKENS, null)?.let { old ->
            val merged = PairedPhones.fromTokens(old) + PairedPhones.parse(prefs.getString(KEY_PHONES, null))
            prefs.edit().remove(KEY_TOKENS).putString(KEY_PHONES, PairedPhones.format(merged)).apply()
        }
        return PairedPhones.parse(prefs.getString(KEY_PHONES, null))
    }

    /** Lets that phone in no more; it has to pair again. */
    @Synchronized
    fun forget(token: String) {
        savePhones(phones().filter { it.token != token })
    }

    private fun savePhones(phones: List<PairedPhone>) {
        prefs()?.edit()?.putString(KEY_PHONES, PairedPhones.format(phones))?.apply()
    }

    private fun unpair(request: Http.Request): Http.Response {
        if (request.method != "POST") return json(405, JSONObject())
        request.headers["x-token"]?.let(::forget)
        return json(200, JSONObject())
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
        main.post { ask(device) { approved -> requests.answer(id, approved, if (approved) issueToken(device) else null) } }
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
        !token.isNullOrEmpty() && phones().any { it.token == token }

    private fun state(): JSONObject {
        val state = onMain { host?.companionState() } ?: JSONObject().put("idle", true)
        return state.put("transcriptVersion", transcriptVersion).put("transcriptStatus", transcriptStatus).put("castCount", cast.size).put("castVersion", castVersion)
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

    /**
     * Pairing by camera: the phone's scanner asks for the invisible code, reads it off the picture,
     * and answers with it (beaconPair). Strength is how far each cell moves, in 8-bit levels.
     */
    private fun beacon(request: Http.Request, paired: Boolean): Http.Response {
        if (request.method != "POST") return json(405, JSONObject())
        val body = try {
            JSONObject(request.body)
        } catch (e: Exception) {
            return json(400, JSONObject())
        }
        val seconds = body.optInt("seconds", BEACON_SECONDS).coerceAtMost(MAX_BEACON_SECONDS)
        if (seconds <= 0) {
            beaconCode = null
            onMain { beaconScreen?.hideBeacon() }
            return json(200, JSONObject().put("showing", false))
        }
        if (!paired && !synchronized(beaconGuard) { beaconGuard.canStart() }) return json(429, JSONObject().put("error", "wait"))
        val code = beaconCode ?: random.nextInt(1 shl BeaconPattern.CODE_BITS).also { beaconCode = it }
        val strength = body.optInt("strength", BEACON_STRENGTH)
        val refresh = onMain { beaconScreen?.showBeacon(BeaconPattern.cells(code), strength, seconds) }
            ?: return json(503, JSONObject().put("error", "screen"))
        if (refresh < 0) {
            beaconCode = null
            return json(409, JSONObject().put("error", "refresh").put("refresh", -refresh))
        }
        return json(200, JSONObject().put("showing", true).put("refresh", refresh).put("strength", strength)
            .put("cols", BeaconPattern.COLS).put("rows", BeaconPattern.ROWS))
    }

    /** The phone read the code: the right one lets it in, a few wrong ones end the code for a while. */
    private fun beaconPair(request: Http.Request): Http.Response {
        val body = try {
            JSONObject(request.body)
        } catch (e: Exception) {
            return json(400, JSONObject())
        }
        val name = deviceName(body, PairedPhones.UNNAMED)
        synchronized(beaconGuard) {
            val showing = beaconCode ?: return json(409, JSONObject().put("error", "showing"))
            if (body.optInt("code", -1) != showing) {
                if (beaconGuard.miss()) {
                    beaconCode = null
                    onMain { beaconScreen?.hideBeacon() }
                }
                return json(403, JSONObject().put("error", "code"))
            }
            beaconGuard.reset()
            beaconCode = null
        }
        val token = issueToken(name)
        onMain {
            beaconScreen?.hideBeacon()
            beaconScreen?.phoneConnected(name)
        }
        Log.i(TAG, "Paired $name by camera")
        return json(200, JSONObject().put("token", token).put("name", SERVICE_NAME))
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

    /** Before phones had names: a set of bare tokens. */
    private const val KEY_TOKENS = "tokens"
    private const val KEY_PHONES = "phones"
    private const val MAX_WRONG_GUESSES = 5
    private const val LIVE_REPEAT_MS = 10_000L
    private const val TAG = "PhoneCompanion"
    private const val SERVICE_TYPE = "_tvplayer._tcp"
    private const val SERVICE_NAME = "TV Player"
    private const val MAX_DEVICE_NAME = 40
    private const val WAIT_MS = 25_000L
    private const val WAIT_STEP_MS = 600L
    private const val BEACON_SECONDS = 20
    private const val MAX_BEACON_SECONDS = 60
    private const val BEACON_STRENGTH = 4
    private val ACTIONS = setOf("play", "pause", "toggle", "seek", "skip")
}
