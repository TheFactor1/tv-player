package com.thefactor1.player.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.IBinder
import org.json.JSONObject

/**
 * Keeps the phone in step with the TV: a long poll that answers whenever what's playing changes.
 * The ongoing notification shows the film with play and pause, and pops up once when a new one
 * starts; tapping it opens the companion. Runs while the phone is paired.
 */
class NowPlayingService : Service() {
    private val store by lazy { TvStore(this) }
    private val notes by lazy { getSystemService(NotificationManager::class.java) }
    @Volatile private var running = false
    private var worker: Thread? = null
    private var announced: String? = null
    /** The soundtrack already put on the phone, so it's announced once per film. */
    private var soundtrackShown: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val note = build(getString(R.string.note_connected), getString(R.string.note_idle), playing = null, alert = false)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTE_ID, note)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!store.paired) {
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_ACTION)?.let { action ->
            Thread { store.api()?.let { api -> runCatching { api.control(action) } } }.start()
        }
        if (!running) {
            running = true
            worker = Thread(::follow, "now-playing").apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }

    /** Follows the TV until stopped: wait for a change, show it, repeat; back off while it's away. */
    private fun follow() {
        var key: String? = null
        var failures = 0
        while (running) {
            val api = (if (failures >= RELOCATE_AFTER) Locate.current(this, store) else store.api())
            try {
                if (api == null) throw java.io.IOException("no address")
                val state = api.waitForChange(key)
                key = state.optString("key")
                failures = 0
                show(state)
            } catch (e: TvApi.Unpaired) {
                store.token = null
                notes.notify(NOTE_ID, build(getString(R.string.app_name), getString(R.string.note_reconnect), playing = null, alert = false))
                running = false
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return
            } catch (e: Exception) {
                if (!running) return
                failures++
                key = null
                if (failures == 1) {
                    notes.notify(NOTE_ID, build(getString(R.string.note_connected), getString(R.string.note_away), playing = null, alert = false))
                }
                try {
                    Thread.sleep(backoff(failures))
                } catch (interrupted: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun show(state: JSONObject) {
        if (state.optBoolean("idle")) {
            notes.notify(NOTE_ID, build(getString(R.string.note_connected), getString(R.string.note_idle), playing = null, alert = false))
            return
        }
        val title = state.optString("title").ifEmpty { getString(R.string.app_name) }
        val playing = state.optBoolean("playing")
        // Pop up once per film, not on every pause.
        val alert = title != announced
        announced = title
        val detail = getString(if (playing) R.string.note_playing else R.string.note_paused)
        notes.notify(NOTE_ID, build(title, detail, playing, alert))
        state.optJSONObject("soundtrack")?.let(::showSoundtrack)
    }

    /**
     * At the credits the TV marks the soundtrack as sent: one notification with the album, opening
     * the companion's Music tab, and buttons straight into the music apps.
     */
    private fun showSoundtrack(album: JSONObject) {
        val id = album.optString("id")
        if (!album.optBoolean("sent") || id.isEmpty() || id == soundtrackShown) return
        soundtrackShown = id
        val name = album.optString("album")
        val artist = album.optString("artist").ifEmpty { null }
        val query = listOfNotNull(name, artist).joinToString(" ")
        val open = PendingIntent.getActivity(
            this, MUSIC_NOTE_ID,
            Intent(this, CompanionActivity::class.java)
                .putExtra(CompanionActivity.EXTRA_MUSIC, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.note_soundtrack, name))
            .setContentText(artist ?: getString(R.string.note_soundtrack_detail))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setColor(getColor(R.color.ground))
        MUSIC_APPS.forEachIndexed { i, (label, url) ->
            val view = PendingIntent.getActivity(
                this, MUSIC_NOTE_ID + 1 + i,
                Intent(Intent.ACTION_VIEW, Uri.parse(url + Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(null as Icon?, getString(label), view).build())
        }
        notes.notify(MUSIC_NOTE_ID, builder.build())
    }

    private fun build(title: String, detail: String, playing: Boolean?, alert: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(getColor(R.color.ground))
            .setOnlyAlertOnce(!alert)
        if (playing != null) {
            val action = if (playing) "pause" else "play"
            val press = PendingIntent.getService(
                this, if (playing) 1 else 2, Intent(this, NowPlayingService::class.java).putExtra(EXTRA_ACTION, action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val icon = Icon.createWithResource(this, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            val label = getString(if (playing) R.string.action_pause else R.string.action_play)
            builder.addAction(Notification.Action.Builder(icon, label, press).build())
            builder.setStyle(Notification.MediaStyle().setShowActionsInCompactView(0))
        }
        return builder.build()
    }

    private fun createChannel() {
        // High so a new film pops up; silent, since it's the TV that makes the noise.
        val channel = NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = getString(R.string.channel_detail)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        notes.createNotificationChannel(channel)
    }

    private fun backoff(failures: Int): Long = (BACKOFF_MS * failures).coerceAtMost(MAX_BACKOFF_MS)

    companion object {
        private const val CHANNEL = "now_playing"
        private const val NOTE_ID = 1
        private const val MUSIC_NOTE_ID = 2
        /** Search links each app opens itself when it's installed, or in the browser when it isn't. */
        private val MUSIC_APPS = listOf(
            R.string.music_spotify to "https://open.spotify.com/search/",
            R.string.music_apple to "https://music.apple.com/search?term=",
            R.string.music_youtube to "https://music.youtube.com/search?q=",
        )
        private const val EXTRA_ACTION = "action"
        private const val RELOCATE_AFTER = 3
        private const val BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 15_000L

        fun start(context: Context) {
            if (!TvStore(context).paired) return
            try {
                context.startForegroundService(Intent(context, NowPlayingService::class.java))
            } catch (e: Exception) {
                // Not allowed from here (e.g. the app is in the background); the next launch starts it.
            }
        }
    }
}
