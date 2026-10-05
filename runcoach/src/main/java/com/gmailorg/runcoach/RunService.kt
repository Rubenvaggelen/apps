package com.gmailorg.runcoach

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class RunService : Service(), LocationListener, SensorEventListener {

    companion object {
        const val ACTION_START = "com.gmailorg.runcoach.START"
        const val ACTION_START_NOW = "com.gmailorg.runcoach.START_NOW"
        const val ACTION_PAUSE = "com.gmailorg.runcoach.PAUSE"
        const val ACTION_RESUME = "com.gmailorg.runcoach.RESUME"
        const val ACTION_STOP = "com.gmailorg.runcoach.STOP"

        private const val EXTRA_TARGET = "target"
        private const val EXTRA_GOAL = "goal"
        private const val EXTRA_LEVEL = "level"
        private const val EXTRA_VOICE = "voice"
        private const val EXTRA_MALE = "male"
        const val MIN_SAVE_DISTANCE_M = 50.0          // kortere pogingen komen niet in de geschiedenis
        private const val CHANNEL_ID = "run_tracking"
        private const val NOTIF_ID = 4242

        // GPS-filters
        private const val GOOD_FIX_ACCURACY = 20f     // nodig om te starten
        private const val MAX_ACCURACY = 25f          // slechtere metingen negeren
        private const val MAX_SPEED_MS = 9.0          // sprongen boven ~2:00/km negeren
        private const val PACE_WINDOW_MS = 25_000L    // glijdend venster huidig tempo
        private const val CADENCE_WINDOW_MS = 60_000L

        fun start(ctx: Context, targetM: Double?, goalPace: Int?, level: Int, voice: Int, male: Boolean) {
            if (!RunAccessRegistry.allowed(ctx)) return
            val i = Intent(ctx, RunService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TARGET, targetM ?: -1.0)
                .putExtra(EXTRA_GOAL, goalPace ?: -1)
                .putExtra(EXTRA_LEVEL, level)
                .putExtra(EXTRA_VOICE, voice)
                .putExtra(EXTRA_MALE, male)
            ctx.startForegroundService(i)
        }

        fun action(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, RunService::class.java).setAction(action))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var speaker: Speaker
    private lateinit var coach: Coach
    private val wearListener: (RunSnapshot) -> Unit = { WearBridge.publish(this, it) }
    private lateinit var lm: LocationManager
    private lateinit var sm: SensorManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var status = RunStatus.IDLE
    private var targetM: Double? = null
    private var goalPace: Int? = null
    private var level = 1

    // afstand / tijd
    private var distance = 0.0
    private var accumulatedMs = 0L
    private var runStartRealtime = 0L
    private var anchor: Location? = null
    private var lastAccuracy: Float? = null
    private var currentPace: Double? = null
    private val paceWindow = ArrayDeque<Pair<Long, Double>>()
    private val splits = mutableListOf<Split>()
    private var lastSplitMs = 0L

    // stappen
    private var stepsAvailable = false
    private var stepBase: Float? = null
    private var lastRawSteps: Float? = null
    private var pausedRawSteps: Float? = null
    private var stepOffset = 0f
    private var steps = 0
    private var cadence: Int? = null
    private val stepWindow = ArrayDeque<Pair<Long, Int>>()

    private var listening = false
    private var tickCount = 0

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 1000L)
        }
    }

    private val finalStop = Runnable {
        releaseWakeLock()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        speaker = Speaker(this)
        coach = Coach(speaker)
        lm = getSystemService(LocationManager::class.java)
        sm = getSystemService(SensorManager::class.java)
        createChannel()
        RunRepository.addListener(wearListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_STOP && !RunAccessRegistry.allowed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> startRun(intent)
            ACTION_START_NOW -> if (status == RunStatus.WAITING_GPS) beginRunning(null)
            ACTION_PAUSE -> pauseRun()
            ACTION_RESUME -> resumeRun()
            ACTION_STOP -> stopRun()
        }
        if (status == RunStatus.IDLE) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RunRepository.removeListener(wearListener)
        handler.removeCallbacksAndMessages(null)
        stopListening()
        speaker.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ run-flow

    private fun startRun(intent: Intent) {
        handler.removeCallbacks(finalStop)
        if (status == RunStatus.RUNNING || status == RunStatus.PAUSED || status == RunStatus.WAITING_GPS) {
            goForeground()
            return
        }
        targetM = intent.getDoubleExtra(EXTRA_TARGET, -1.0).takeIf { it > 0 }
        goalPace = intent.getIntExtra(EXTRA_GOAL, -1).takeIf { it > 0 }
        level = intent.getIntExtra(EXTRA_LEVEL, 1)
        val voice = intent.getIntExtra(EXTRA_VOICE, Coach.VOICE_NORMAL)

        resetStats()
        status = RunStatus.WAITING_GPS
        if (!goForeground()) {
            status = RunStatus.IDLE
            RunRepository.update(RunSnapshot())
            return
        }
        acquireWakeLock()
        speaker.setVoice(voice, intent.getBooleanExtra(EXTRA_MALE, false))
        coach.configure(level, goalPace, targetM, voice)
        startListening()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun beginRunning(fix: Location?) {
        status = RunStatus.RUNNING
        runStartRealtime = SystemClock.elapsedRealtime()
        accumulatedMs = 0L
        anchor = fix
        paceWindow.clear()
        stepWindow.clear()
        stepBase = lastRawSteps   // nulpunt van de stappenteller
        stepOffset = 0f
        steps = 0
        coach.onStart(fix != null)
        publish()
        updateNotification()
    }

    private fun pauseRun() {
        if (status != RunStatus.RUNNING) return
        accumulatedMs += SystemClock.elapsedRealtime() - runStartRealtime
        status = RunStatus.PAUSED
        anchor = null
        currentPace = null
        pausedRawSteps = lastRawSteps
        coach.onPause()
        publish()
        updateNotification()
    }

    private fun resumeRun() {
        if (status != RunStatus.PAUSED) return
        runStartRealtime = SystemClock.elapsedRealtime()
        val p = pausedRawSteps
        val l = lastRawSteps
        if (p != null && l != null) stepOffset += (l - p)  // stappen tijdens pauze niet meetellen
        pausedRawSteps = null
        paceWindow.clear()
        stepWindow.clear()
        status = RunStatus.RUNNING
        coach.onResume()
        publish()
        updateNotification()
    }

    private fun stopRun() {
        if (status == RunStatus.FINISHED) return
        handler.removeCallbacks(tick)
        stopListening()
        if (status == RunStatus.IDLE || status == RunStatus.WAITING_GPS) {
            status = RunStatus.IDLE
            RunRepository.update(RunSnapshot())
            stopForeground(STOP_FOREGROUND_REMOVE)
            releaseWakeLock()
            return
        }
        if (status == RunStatus.RUNNING) {
            accumulatedMs += SystemClock.elapsedRealtime() - runStartRealtime
        }
        status = RunStatus.FINISHED
        currentPace = null
        val snap = snapshot()
        if (snap.distanceM >= MIN_SAVE_DISTANCE_M) History.add(this, snap)
        RunRepository.update(snap)
        coach.onFinish(snap)
        stopForeground(STOP_FOREGROUND_REMOVE)
        // even laten doorpraten voordat de service afsluit
        handler.postDelayed(finalStop, 12_000L)
    }

    private fun resetStats() {
        distance = 0.0
        accumulatedMs = 0L
        runStartRealtime = 0L
        anchor = null
        lastAccuracy = null
        currentPace = null
        paceWindow.clear()
        splits.clear()
        lastSplitMs = 0L
        stepBase = null
        pausedRawSteps = null
        stepOffset = 0f
        steps = 0
        cadence = null
        stepWindow.clear()
        tickCount = 0
    }

    private fun elapsed(): Long =
        if (status == RunStatus.RUNNING) accumulatedMs + (SystemClock.elapsedRealtime() - runStartRealtime)
        else accumulatedMs

    // ------------------------------------------------------------------ elke seconde

    private fun onTick() {
        val e = elapsed()
        if (status == RunStatus.RUNNING) {
            // huidig tempo over glijdend venster
            paceWindow.addLast(e to distance)
            while (paceWindow.size > 2 && e - paceWindow.first().first > PACE_WINDOW_MS) paceWindow.removeFirst()
            val (t0, d0) = paceWindow.first()
            val dtSec = (e - t0) / 1000.0
            val dd = distance - d0
            currentPace = if (dtSec >= 10 && dd >= 15) dtSec / (dd / 1000.0) else null

            // kilometertijden
            while (distance >= (splits.size + 1) * 1000.0) {
                splits.add(Split(splits.size + 1, e - lastSplitMs, e))
                lastSplitMs = e
            }

            // cadans over de laatste minuut
            stepWindow.addLast(e to steps)
            while (stepWindow.size > 2 && e - stepWindow.first().first > CADENCE_WINDOW_MS) stepWindow.removeFirst()
            val (st0, s0) = stepWindow.first()
            val minutes = (e - st0) / 60_000.0
            cadence = if (stepsAvailable && minutes >= 0.33 && steps > s0) ((steps - s0) / minutes).roundToInt() else null
        }

        val snap = snapshot()
        RunRepository.update(snap)
        if (status == RunStatus.RUNNING) coach.onTick(snap)
        tickCount++
        if (tickCount % 5 == 0) updateNotification()
    }

    private fun snapshot() = RunSnapshot(
        status = status,
        distanceM = distance,
        elapsedMs = elapsed(),
        currentPaceSecPerKm = currentPace,
        steps = steps,
        cadence = cadence,
        splits = splits.toList(),
        gpsAccuracyM = lastAccuracy,
        targetDistanceM = targetM,
        goalPaceSecPerKm = goalPace,
        coachLevel = level,
        stepsAvailable = stepsAvailable
    )

    private fun publish() = RunRepository.update(snapshot())

    // ------------------------------------------------------------------ GPS

    override fun onLocationChanged(location: Location) {
        lastAccuracy = if (location.hasAccuracy()) location.accuracy else null
        val acc = if (location.hasAccuracy()) location.accuracy else 99f

        if (status == RunStatus.WAITING_GPS) {
            if (acc <= GOOD_FIX_ACCURACY) beginRunning(location)
            return
        }
        if (status != RunStatus.RUNNING) return
        if (acc > MAX_ACCURACY) return

        val a = anchor
        if (a == null) {
            anchor = location
            return
        }
        val d = a.distanceTo(location).toDouble()
        val dt = (location.elapsedRealtimeNanos - a.elapsedRealtimeNanos) / 1_000_000_000.0
        if (dt <= 0) return

        if (d / dt > MAX_SPEED_MS) {
            // onmogelijke sprong: negeren, na een lang gat opnieuw ankeren
            if (dt > 10) anchor = location
            return
        }
        // pas meetellen als je echt verplaatst bent (voorkomt GPS-ruis bij stilstaan)
        val threshold = max(3.0, min(acc.toDouble(), 20.0) * 0.5)
        if (d >= threshold) {
            distance += d
            anchor = location
        }
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // ------------------------------------------------------------------ stappen

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        when (e.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val raw = e.values[0]
                lastRawSteps = raw
                if (status == RunStatus.RUNNING) {
                    val base = stepBase ?: raw.also { stepBase = it }
                    steps = max(0, (raw - base - stepOffset).roundToInt())
                }
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                if (status == RunStatus.RUNNING) steps++
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ------------------------------------------------------------------ luisteren

    private fun has(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startListening() {
        if (listening) return
        listening = true
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (e: Exception) {
            // geen GPS of geen toestemming
        }
        val counter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val detector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val permOk = Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACTIVITY_RECOGNITION)
        stepsAvailable = permOk && (counter != null || detector != null)
        if (stepsAvailable) {
            if (counter != null) sm.registerListener(this, counter, SensorManager.SENSOR_DELAY_UI)
            else sm.registerListener(this, detector, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        try { lm.removeUpdates(this) } catch (e: Exception) { }
        sm.unregisterListener(this)
    }

    // ------------------------------------------------------------------ foreground / melding

    private fun goForeground(): Boolean {
        val n = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                if (Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.ACTIVITY_RECOGNITION)) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                }
                startForeground(NOTIF_ID, n, type)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Hardloop-tracking", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun updateNotification() {
        if (status == RunStatus.IDLE || status == RunStatus.FINISHED) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun serviceIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, RunService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun action(title: String, action: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_run), title, serviceIntent(action, code)).build()

    private fun buildNotification(): Notification {
        val title = when (status) {
            RunStatus.WAITING_GPS -> "GPS zoeken…"
            RunStatus.PAUSED -> "Gepauzeerd · ${Fmt.km(distance)} km"
            else -> "Hardlopen · ${Fmt.km(distance)} km"
        }
        val text = "${Fmt.time(elapsed())} · tempo ${Fmt.pace(currentPace)} /km"
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_WORKOUT)

        when (status) {
            RunStatus.RUNNING -> b.addAction(action("Pauze", ACTION_PAUSE, 1))
            RunStatus.PAUSED -> b.addAction(action("Hervatten", ACTION_RESUME, 2))
            else -> {}
        }
        b.addAction(action("Stop", ACTION_STOP, 3))
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    // ------------------------------------------------------------------ wakelock

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "runcoach:run").apply {
            setReferenceCounted(false)
            acquire(5 * 60 * 60 * 1000L)  // max 5 uur
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
        wakeLock = null
    }
}
