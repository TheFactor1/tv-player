package com.thefactor1.player.phone

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The TV's companion API. Every call blocks, so call it off the main thread. */
class TvApi(val host: String, val port: Int, private val token: String?) {

    /** The TV turned the token down: it was forgotten, so the phone has to ask again. */
    class Unpaired : IOException("not paired")

    val pageUrl: String get() = "http://$host:$port/" + (token?.let { "#token=$it" } ?: "")

    /** Asks to connect; the TV shows a card. Returns the request id, or the reason it couldn't ask. */
    fun hello(device: String): Result<String> = try {
        val (status, body) = call("POST", "/api/hello", JSONObject().put("device", device), CALL_TIMEOUT_MS)
        when (status) {
            200 -> Result.success(body.getString("request"))
            else -> Result.failure(IOException(body.optString("error", "status $status")))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** pending, approved (with the token), denied, expired or unknown. */
    fun helloAnswer(request: String): Pair<String, String?> {
        val (_, body) = call("GET", "/api/hello?id=" + URLEncoder.encode(request, "UTF-8"), null, CALL_TIMEOUT_MS)
        return body.optString("status") to body.optString("token").ifEmpty { null }
    }

    /** Waits until what's playing differs from [key] (the TV answers within about 25 s regardless). */
    fun waitForChange(key: String?): JSONObject {
        val path = "/api/wait" + (key?.let { "?key=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        val (status, body) = call("GET", path, null, WAIT_TIMEOUT_MS)
        if (status == 401) throw Unpaired()
        if (status != 200) throw IOException("status $status")
        return body
    }

    fun control(action: String) {
        call("POST", "/api/control", JSONObject().put("action", action), CALL_TIMEOUT_MS)
    }

    /** Asks the TV to show the invisible code for [seconds] (0 stops it). The status and the TV's answer. */
    fun beacon(strength: Int, seconds: Int): Pair<Int, JSONObject> =
        call("POST", "/api/beacon", JSONObject().put("strength", strength).put("seconds", seconds), CALL_TIMEOUT_MS)

    /** Answers the invisible code read off the screen; the token when it's this TV's code. */
    fun beaconPair(code: Int, device: String): String? {
        val (status, body) = call("POST", "/api/beacon/pair", JSONObject().put("code", code).put("device", device), CALL_TIMEOUT_MS)
        return body.optString("token").takeIf { status == 200 && it.isNotEmpty() }
    }

    /** Pairs with the 4-digit code the TV shows in its settings. The token, or why not ("code", or a status). */
    fun pair(code: String, device: String): Result<String> = try {
        val (status, body) = call("POST", "/api/pair", JSONObject().put("code", code).put("device", device), CALL_TIMEOUT_MS)
        if (status == 200 && body.optString("token").isNotEmpty()) Result.success(body.getString("token"))
        else Result.failure(IOException(body.optString("error", "status $status")))
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Tells the TV to forget this phone. */
    fun unpair() {
        call("POST", "/api/unpair", JSONObject(), PROBE_TIMEOUT_MS)
    }

    /** True when the TV answers at this address with this token. */
    fun reachable(): Boolean = try {
        call("GET", "/api/state", null, PROBE_TIMEOUT_MS).first == 200
    } catch (e: Exception) {
        false
    }

    private fun call(method: String, path: String, body: JSONObject?, timeoutMs: Int): Pair<Int, JSONObject> {
        val connection = (URL("http://$host:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = timeoutMs
            token?.let { setRequestProperty("X-Token", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            body?.let { json -> connection.outputStream.use { it.write(json.toString().toByteArray()) } }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return status to (if (text.isBlank()) JSONObject() else JSONObject(text))
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 3_000
        const val CALL_TIMEOUT_MS = 8_000
        const val WAIT_TIMEOUT_MS = 40_000
    }
}
