package com.vkvych.remotecontrol.child.server

import android.util.Log
import com.vkvych.remotecontrol.child.control.CommandExecutor
import com.vkvych.remotecontrol.child.control.DeviceInfoProvider
import com.vkvych.remotecontrol.child.control.DeviceStateRepository
import com.vkvych.remotecontrol.child.data.PairingStore
import com.vkvych.remotecontrol.protocol.AUTH_SCHEME
import com.vkvych.remotecontrol.protocol.CommandReply
import com.vkvych.remotecontrol.protocol.Credentials
import com.vkvych.remotecontrol.protocol.DEFAULT_PORT
import com.vkvych.remotecontrol.protocol.DecodedClientMessage
import com.vkvych.remotecontrol.protocol.ErrorCode
import com.vkvych.remotecontrol.protocol.ErrorResponse
import com.vkvych.remotecontrol.protocol.Failure
import com.vkvych.remotecontrol.protocol.HealthResponse
import com.vkvych.remotecontrol.protocol.MessageCodec
import com.vkvych.remotecontrol.protocol.PROTOCOL_VERSION
import com.vkvych.remotecontrol.protocol.PairRequest
import com.vkvych.remotecontrol.protocol.PairResponse
import com.vkvych.remotecontrol.protocol.ProtocolJson
import com.vkvych.remotecontrol.protocol.Routes
import com.vkvych.remotecontrol.protocol.ServerMessage
import com.vkvych.remotecontrol.protocol.StateUpdate
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * The control endpoint the parent device talks to.
 *
 * Bound to all interfaces so it is reachable both over the home Wi-Fi and over the family tailnet;
 * Tailscale supplies the encryption and the stable address, and the bearer token supplies the
 * authorisation. Traffic is plain `ws://`, which is safe on a tailnet but is exactly why the token
 * check below is not optional — anything else on the LAN can reach this port.
 */
class ControlServer(
    private val pairingStore: PairingStore,
    private val pairingSession: PairingSession,
    private val stateRepository: DeviceStateRepository,
    private val commandExecutor: CommandExecutor,
    private val deviceInfo: DeviceInfoProvider,
) {

    private var server: EmbeddedServer<*, *>? = null

    private val _connectionCount = MutableStateFlow(0)

    /** Number of controllers currently attached. Drives the foreground-service notification. */
    val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

    @Synchronized
    fun start(port: Int = DEFAULT_PORT) {
        if (server != null) return
        Log.i(TAG, "Starting control server on port $port")
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }
            .also { it.start(wait = false) }
    }

    @Synchronized
    fun stop() {
        server?.let {
            Log.i(TAG, "Stopping control server")
            it.stop(SHUTDOWN_GRACE_MILLIS, SHUTDOWN_TIMEOUT_MILLIS)
        }
        server = null
        _connectionCount.value = 0
    }

    private fun Application.module() {
        install(WebSockets) {
            // Keeps NAT and Tailscale paths warm, and surfaces a dead controller reasonably fast.
            pingPeriodMillis = 15.seconds.inWholeMilliseconds
            timeoutMillis = 30.seconds.inWholeMilliseconds
        }
        install(ContentNegotiation) { json(ProtocolJson) }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                Log.e(TAG, "Unhandled failure serving ${call.request.uri}", cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(ErrorCode.INTERNAL, cause.message ?: "Unexpected failure"),
                )
            }
        }

        routing {
            get(Routes.HEALTH) {
                call.respond(
                    HealthResponse(
                        deviceName = deviceInfo.deviceName(),
                        paired = pairingStore.isPaired,
                    ),
                )
            }

            post(Routes.PAIR) {
                val request = try {
                    call.receive<PairRequest>()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Malformed pairing request", e)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ErrorCode.INVALID_ARGUMENT, "Malformed pairing request"),
                    )
                    return@post
                }

                if (request.protocolVersion != PROTOCOL_VERSION) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            ErrorCode.PROTOCOL_VERSION_MISMATCH,
                            "Controller speaks protocol v${request.protocolVersion}, " +
                                "this agent speaks v$PROTOCOL_VERSION. Update both apps.",
                        ),
                    )
                    return@post
                }

                if (!pairingSession.consume(request.code)) {
                    Log.w(TAG, "Rejected pairing attempt from ${call.request.origin.remoteHost}")
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse(
                            ErrorCode.UNAUTHORIZED,
                            "That pairing code is not valid or has expired. " +
                                "Show a new code on the child device and try again.",
                        ),
                    )
                    return@post
                }

                val token = Credentials.generateToken()
                pairingStore.savePairing(token, request.controllerName)
                Log.i(TAG, "Paired with '${request.controllerName}'")

                call.respond(
                    PairResponse(
                        token = token,
                        deviceId = pairingStore.deviceId,
                        deviceName = deviceInfo.deviceName(),
                    ),
                )
            }

            webSocket(Routes.CONTROL) {
                val token = call.request.headers[HttpHeaders.Authorization]?.bearerToken()
                if (token == null || !pairingStore.verifyToken(token)) {
                    Log.w(TAG, "Unauthorized control attempt from ${call.request.origin.remoteHost}")
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                    return@webSocket
                }

                _connectionCount.update { it + 1 }
                try {
                    stateRepository.refresh()

                    // StateFlow replays its current value, so this both delivers the opening
                    // snapshot and keeps pushing whenever the device changes on its own.
                    val pushJob = launch {
                        stateRepository.state.collect { state ->
                            send(Frame.Text(MessageCodec.encode(StateUpdate(state))))
                        }
                    }

                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            send(Frame.Text(MessageCodec.encode(respondTo(frame.readText()))))
                        }
                    } finally {
                        pushJob.cancel()
                    }
                } finally {
                    _connectionCount.update { it - 1 }
                }
            }
        }
    }

    private fun respondTo(payload: String): ServerMessage =
        when (val decoded = MessageCodec.decodeClientMessage(payload)) {
            is DecodedClientMessage.Parsed ->
                CommandReply(
                    id = decoded.message.id,
                    outcome = commandExecutor.execute(decoded.message.command),
                )

            is DecodedClientMessage.Undecodable ->
                CommandReply(
                    id = decoded.id ?: UNCORRELATED_ID,
                    outcome = Failure(decoded.code, decoded.reason),
                )
        }

    private companion object {
        const val TAG = "ControlServer"
        const val SHUTDOWN_GRACE_MILLIS = 500L
        const val SHUTDOWN_TIMEOUT_MILLIS = 2_000L

        /** Used when a malformed payload did not even carry a usable correlation id. */
        const val UNCORRELATED_ID = "unknown"
    }
}

/** Extracts the credential from an `Authorization: Bearer <token>` header. */
private fun String.bearerToken(): String? =
    takeIf { it.startsWith("$AUTH_SCHEME ", ignoreCase = true) }
        ?.substring(AUTH_SCHEME.length + 1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
