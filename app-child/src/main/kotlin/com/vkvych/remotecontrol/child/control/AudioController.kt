package com.vkvych.remotecontrol.child.control

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.core.content.edit
import com.vkvych.remotecontrol.protocol.AudioStream
import com.vkvych.remotecontrol.protocol.RingerMode
import com.vkvych.remotecontrol.protocol.StreamVolume

/**
 * Reads and applies the device's audio settings through the public [AudioManager] API.
 *
 * No vendor API is needed here — Samsung exposes the standard streams. The Knox and Device Owner
 * APIs only become necessary for app management in later phases.
 */
class AudioController(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    /** Levels stashed by [setMuted], so unmuting restores what the child was actually listening at. */
    private val mutePrefs = appContext.getSharedPreferences("mute_memory", Context.MODE_PRIVATE)

    /**
     * Whether the app may change ring/notification volume while Do Not Disturb is on.
     *
     * Without it Android throws a [SecurityException] for those streams whenever DND is active,
     * which is exactly when a parent is most likely to reach for the controls.
     */
    fun hasDndAccess(): Boolean = notificationManager.isNotificationPolicyAccessGranted

    fun volumes(): List<StreamVolume> = AudioStream.entries.map { stream ->
        val id = stream.androidStreamType
        StreamVolume(
            stream = stream,
            level = audioManager.getStreamVolume(id),
            max = audioManager.getStreamMaxVolume(id),
            min = audioManager.getStreamMinVolume(id),
        )
    }

    fun ringerMode(): RingerMode = when (audioManager.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
        AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
        else -> RingerMode.NORMAL
    }

    /** Sets [stream] to [level], clamped to the range the device actually supports. */
    fun setVolume(stream: AudioStream, level: Int) {
        val id = stream.androidStreamType
        val clamped = level.coerceIn(audioManager.getStreamMinVolume(id), audioManager.getStreamMaxVolume(id))
        audioManager.setStreamVolume(id, clamped, 0)
    }

    fun setRingerMode(mode: RingerMode) {
        audioManager.ringerMode = when (mode) {
            RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
            RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
        }
    }

    /**
     * Silences or restores the streams in [MUTABLE_STREAMS].
     *
     * Alarms and in-call audio are left alone on purpose: a parent muting a tablet from another
     * room should not also disable the alarm that wakes the child for school, and muting a live
     * call is never what the button means.
     */
    fun setMuted(muted: Boolean) {
        if (muted) {
            // Ignore a second mute so it cannot overwrite the remembered levels with zeroes.
            if (mutePrefs.contains(KEY_MUTED)) return

            val levels = MUTABLE_STREAMS.associateWith { audioManager.getStreamVolume(it.androidStreamType) }
            mutePrefs.edit {
                putBoolean(KEY_MUTED, true)
                levels.forEach { (stream, level) -> putInt(stream.name, level) }
            }
            MUTABLE_STREAMS.forEach { stream ->
                val id = stream.androidStreamType
                audioManager.setStreamVolume(id, audioManager.getStreamMinVolume(id), 0)
            }
        } else {
            MUTABLE_STREAMS.forEach { stream ->
                val remembered = mutePrefs.getInt(stream.name, -1)
                if (remembered >= 0) setVolume(stream, remembered)
            }
            mutePrefs.edit { clear() }
        }
    }

    /** True when the streams were silenced by [setMuted] and not yet restored. */
    fun isMuted(): Boolean = mutePrefs.getBoolean(KEY_MUTED, false)

    private companion object {
        const val KEY_MUTED = "muted"

        val MUTABLE_STREAMS = listOf(
            AudioStream.MUSIC,
            AudioStream.RING,
            AudioStream.NOTIFICATION,
            AudioStream.SYSTEM,
        )
    }
}

/** Maps the platform-neutral protocol stream onto the Android constant. */
private val AudioStream.androidStreamType: Int
    get() = when (this) {
        AudioStream.MUSIC -> AudioManager.STREAM_MUSIC
        AudioStream.RING -> AudioManager.STREAM_RING
        AudioStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        AudioStream.ALARM -> AudioManager.STREAM_ALARM
        AudioStream.SYSTEM -> AudioManager.STREAM_SYSTEM
        AudioStream.VOICE_CALL -> AudioManager.STREAM_VOICE_CALL
    }
