package com.vkvych.remotecontrol.child.control

import android.util.Log
import com.vkvych.remotecontrol.protocol.Command
import com.vkvych.remotecontrol.protocol.CommandOutcome
import com.vkvych.remotecontrol.protocol.ErrorCode
import com.vkvych.remotecontrol.protocol.Failure
import com.vkvych.remotecontrol.protocol.GetState
import com.vkvych.remotecontrol.protocol.SetMuted
import com.vkvych.remotecontrol.protocol.SetRingerMode
import com.vkvych.remotecontrol.protocol.SetVolume
import com.vkvych.remotecontrol.protocol.Success

/**
 * Applies a [Command] and reports what happened.
 *
 * Every successful command answers with the post-command [com.vkvych.remotecontrol.protocol.DeviceState],
 * so the controller never has to guess whether its slider landed where it aimed — useful because
 * the device clamps levels to its own supported range.
 */
class CommandExecutor(
    private val audioController: AudioController,
    private val stateRepository: DeviceStateRepository,
) {

    fun execute(command: Command): CommandOutcome = try {
        when (command) {
            GetState -> Success(stateRepository.refresh())

            is SetVolume -> {
                audioController.setVolume(command.stream, command.level)
                Success(stateRepository.refresh())
            }

            is SetRingerMode -> {
                audioController.setRingerMode(command.mode)
                Success(stateRepository.refresh())
            }

            is SetMuted -> {
                audioController.setMuted(command.muted)
                Success(stateRepository.refresh())
            }
        }
    } catch (e: SecurityException) {
        // Overwhelmingly this is ring/notification volume being changed while Do Not Disturb is on
        // without notification-policy access, so say so rather than surfacing a raw stack trace.
        Log.w(TAG, "Refused by the platform: $command", e)
        Failure(
            code = ErrorCode.PERMISSION_REQUIRED,
            message = if (audioController.hasDndAccess()) {
                "The device refused this change: ${e.message}"
            } else {
                "Grant \"Do Not Disturb access\" to the agent app on the child device, " +
                    "then try again."
            },
        )
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Rejected $command", e)
        Failure(ErrorCode.INVALID_ARGUMENT, e.message ?: "Unusable arguments")
    } catch (e: RuntimeException) {
        Log.e(TAG, "Failed to run $command", e)
        Failure(ErrorCode.INTERNAL, e.message ?: e.javaClass.simpleName)
    }

    private companion object {
        const val TAG = "CommandExecutor"
    }
}
