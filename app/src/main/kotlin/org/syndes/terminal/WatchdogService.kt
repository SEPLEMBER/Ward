package org.syndes.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class WatchdogService : Service() {

    companion object {
        const val EXTRA_CMD = "watchdog_cmd"
        const val EXTRA_DELAY_SEC = "watchdog_delay_sec"
        const val CHANNEL_ID = "syndes_watchdog"
        const val NOTIF_ID = 0xC0FFEE
    }

    private val svcScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cmd = intent?.getStringExtra(EXTRA_CMD) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val delaySec = intent.getLongExtra(EXTRA_DELAY_SEC, 0L)
        if (delaySec <= 0L) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (running.compareAndSet(false, true)) {
            // build initial notification
            val notif = buildNotification("Watchdog scheduled: will run in ${formatSimple(delaySec)}", cmd)
            startForeground(NOTIF_ID, notif)

            svcScope.launch {
                try {
                    var remaining = delaySec

                    // update notification periodically; coarse minute ticks as before
                    while (remaining > 0 && isActive) {
                        // update every minute if >=60s otherwise every second
                        if (remaining >= 60L) {
                            val slept = 60L
                            val millis = slept * 1000L
                            var passed = 0L
                            while (passed < millis && isActive) {
                                delay(1000L)
                                passed += 1000L
                            }
                            remaining -= slept
                        } else {
                            delay(1000L)
                            remaining -= 1L
                        }
                        // update notification text
                        val upd = buildNotification("Watchdog: will run in ${formatSimple(remaining)}", cmd)
                        getNotificationManager().notify(NOTIF_ID, upd)
                    }

                    // final update
                    getNotificationManager().notify(NOTIF_ID, buildNotification("Watchdog: executing now...", cmd))
                    // short pause
                    delay(200)

                    // Execute the command — run Terminal.execute in IO
                    val result = withContext(Dispatchers.IO) {
                        try {
                            // Terminal should not rely on Activity-specific context for background commands.
                            // Use applicationContext here.
                            val terminal = Terminal()
                            terminal.execute(cmd, applicationContext) ?: "Info: (no output)"
                        } catch (t: Throwable) {
                            "Error: watchdog execution failed: ${t.message}"
                        }
                    }

                    // Notify result (and write to logs)
                    val resText = buildNotification("Watchdog result: ${shorten(result)}", cmd)
                    getNotificationManager().notify(NOTIF_ID, resText)

                    // If app is in foreground and has a receiver, optionally broadcast the result so UI can append it.
                    try {
                        val b = Intent("org.syndes.terminal.WATCHDOG_RESULT")
                        b.putExtra("cmd", cmd)
                        b.putExtra("result", result)
                        sendBroadcast(b)
                    } catch (_: Throwable) {}
                } finally {
                    // stop service after small delay so user sees final state
                    delay(1500)
                    stopSelf()
                }
            }
        } else {
            // already running a watchdog — you may opt to queue or ignore; here we ignore new start
            val notif = buildNotification("Watchdog already running", cmd)
            getNotificationManager().notify(NOTIF_ID, notif)
        }

        // If killed, don't restart automatically
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        svcScope.cancel()
        running.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String, cmd: String): Notification {
        // create an action that opens the app when clicked
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent ?: Intent(), 
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Syndes: Watchdog")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // замените на ваш ресурс
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getNotificationManager()
            val ch = NotificationChannel(CHANNEL_ID, "Syndes Watchdog", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Watchdog background tasks"
            nm.createNotificationChannel(ch)
        }
    }

    private fun getNotificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun formatSimple(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%dh %02dm %02ds", h, m, s)
        else if (m > 0) String.format("%02dm %02ds", m, s)
        else String.format("%02ds", s)
    }

    private fun shorten(s: String?): String {
        if (s == null) return ""
        return if (s.length <= 200) s else s.substring(0, 200) + "…"
    }
}
