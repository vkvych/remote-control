package com.vkvych.remotecontrol.child.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.vkvych.remotecontrol.child.R
import com.vkvych.remotecontrol.child.agentContainer
import com.vkvych.remotecontrol.child.server.ControlServer
import com.vkvych.remotecontrol.child.ui.MainActivity
import com.vkvych.remotecontrol.protocol.DEFAULT_PORT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hosts the control server for as long as the device is on.
 *
 * A foreground service is the only way to keep a listening socket alive across doze on a modern
 * Galaxy device. It also watches the device's own audio settings so that a child turning the
 * hardware volume keys is pushed to the parent immediately, instead of the parent's sliders
 * quietly drifting out of date.
 */
class AgentService : LifecycleService() {

    private lateinit var server: ControlServer
    private var volumeObserver: ContentObserver? = null
    private var ringerModeReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        val container = agentContainer

        server = ControlServer(
            pairingStore = container.pairingStore,
            pairingSession = container.pairingSession,
            stateRepository = container.stateRepository,
            commandExecutor = container.commandExecutor,
            deviceInfo = container.deviceInfo,
        )

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(connections = 0))

        observeDeviceAudioChanges()
        server.start(DEFAULT_PORT)
        _running.value = true

        lifecycleScope.launch {
            server.connectionCount.collect { connections ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(connections))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Restart if the platform kills us: an agent that silently stops answering is worse than
        // one that briefly disappears.
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        stopObservingDeviceAudioChanges()
        server.stop()
        super.onDestroy()
    }

    /**
     * Watches the two things that change audio behind our back: the volume settings rows, and the
     * ringer mode. Both funnel into a repository refresh, which pushes to connected controllers
     * only when something actually differs.
     */
    private fun observeDeviceAudioChanges() {
        val container = agentContainer

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                container.stateRepository.refresh()
            }
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        volumeObserver = observer

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                container.stateRepository.refresh()
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ringerModeReceiver = receiver
    }

    private fun stopObservingDeviceAudioChanges() {
        volumeObserver?.let { contentResolver.unregisterContentObserver(it) }
        volumeObserver = null

        ringerModeReceiver?.let {
            runCatching { unregisterReceiver(it) }
                .onFailure { error -> Log.w(TAG, "Ringer receiver was already gone", error) }
        }
        ringerModeReceiver = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            // Low: the parent needs to be able to see the agent is alive, the child does not need
            // to be interrupted by it.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(connections: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val text = when {
            !agentContainer.pairingStore.isPaired -> getString(R.string.notification_not_paired)
            connections > 0 -> resources.getQuantityString(
                R.plurals.notification_connected,
                connections,
                connections,
            )

            else -> getString(R.string.notification_waiting)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "agent_status"
        private const val NOTIFICATION_ID = 1

        private val _running = MutableStateFlow(false)

        /** Whether the agent is currently serving. Drives the setup screen's status card. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context.applicationContext, AgentService::class.java),
            )
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, AgentService::class.java),
            )
        }
    }
}
