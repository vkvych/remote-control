package com.vkvych.remotecontrol.child.util

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * An address this device can be reached on.
 *
 * [viaTailscale] marks the one worth typing into the parent app: unlike the Wi-Fi address it does
 * not change when the child moves between networks, and it works from outside the house.
 */
data class LocalAddress(
    val interfaceName: String,
    val address: String,
    val viaTailscale: Boolean,
)

/**
 * Lists the IPv4 addresses the agent is reachable on, so the setup screen can tell the user what
 * to type into the parent app instead of making them hunt through Settings.
 */
object NetworkAddresses {

    fun reachableAddresses(): List<LocalAddress> = try {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses
                    .asSequence()
                    .filterIsInstance<Inet4Address>()
                    .map { address ->
                        LocalAddress(
                            interfaceName = networkInterface.name,
                            address = address.hostAddress.orEmpty(),
                            viaTailscale = address.isTailscaleAddress(),
                        )
                    }
            }
            .filter { it.address.isNotEmpty() }
            // Tailscale first: it is the address that keeps working away from home.
            .sortedByDescending { it.viaTailscale }
            .toList()
    } catch (e: Exception) {
        Log.w(TAG, "Could not enumerate network interfaces", e)
        emptyList()
    }

    /**
     * Tailscale hands out addresses from the CGNAT block 100.64.0.0/10, which nothing else on a
     * home network uses.
     */
    private fun Inet4Address.isTailscaleAddress(): Boolean {
        val octets = address ?: return false
        if (octets.size != 4) return false
        val first = octets[0].toInt() and 0xFF
        val second = octets[1].toInt() and 0xFF
        return first == 100 && second in 64..127
    }

    private const val TAG = "NetworkAddresses"
}
