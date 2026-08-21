package com.vkvych.remotecontrol.parent.net

import com.vkvych.remotecontrol.protocol.ErrorResponse
import com.vkvych.remotecontrol.protocol.HealthResponse
import com.vkvych.remotecontrol.protocol.PROTOCOL_VERSION
import com.vkvych.remotecontrol.protocol.PairRequest
import com.vkvych.remotecontrol.protocol.PairResponse
import com.vkvych.remotecontrol.protocol.ProtocolJson
import com.vkvych.remotecontrol.protocol.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The one-shot HTTP exchange that turns a pairing code into a durable token.
 *
 * Kept separate from [ControlClient] because it has the opposite lifetime: this runs once, with
 * short timeouts and no retries, while the control connection is long-lived and reconnects
 * forever.
 */
class PairingClient {

    private val httpClient = OkHttpClient.Builder()
        // A child device that is awake answers in milliseconds; waiting longer only delays telling
        // the user they typed the wrong address.
        .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Confirms something is listening at [host] before asking the user for a code. */
    suspend fun probe(host: String, port: Int): Result<HealthResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("http://$host:$port${Routes.HEALTH}")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("The agent answered ${response.code}")
                }
                ProtocolJson.decodeFromString<HealthResponse>(body)
            }
        }
    }

    /** Redeems [code] for a token. Fails with a readable message the UI can show verbatim. */
    suspend fun pair(
        host: String,
        port: Int,
        code: String,
        controllerName: String,
    ): Result<PairResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = ProtocolJson.encodeToString(
                PairRequest(code = code, controllerName = controllerName),
            )
            val request = Request.Builder()
                .url("http://$host:$port${Routes.PAIR}")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException(body.readableError(response.code))
                }
                val paired = ProtocolJson.decodeFromString<PairResponse>(body)
                if (paired.protocolVersion != PROTOCOL_VERSION) {
                    throw IOException(
                        "The agent speaks protocol v${paired.protocolVersion} but this app " +
                            "speaks v$PROTOCOL_VERSION. Update both apps.",
                    )
                }
                paired
            }
        }
    }

    /** Prefers the agent's own explanation over an HTTP status code. */
    private fun String.readableError(statusCode: Int): String =
        runCatching { ProtocolJson.decodeFromString<ErrorResponse>(this).message }
            .getOrElse { "The agent rejected the request (HTTP $statusCode)" }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 5L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
