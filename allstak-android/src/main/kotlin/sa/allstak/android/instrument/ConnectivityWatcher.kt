package sa.allstak.android.instrument

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import sa.allstak.android.core.AllStakClient
import sa.allstak.android.core.internal.SdkLogger

/**
 * Emits connectivity breadcrumbs (online/offline + transport type) and, on
 * reconnect, triggers a spool drain so telemetry buffered while offline is
 * replayed promptly. Registered at startup; gracefully a no-op when the
 * permission or service is unavailable.
 */
internal class ConnectivityWatcher(
    private val context: Context,
    private val client: AllStakClient,
) {
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun register() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    client.addBreadcrumb("default", "connectivity", "Network available", "info", null)
                    // Reconnect: replay anything spooled while offline.
                    client.drainSpoolNow()
                }

                override fun onLost(network: Network) {
                    client.addBreadcrumb("default", "connectivity", "Network lost", "warn", null)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val transport = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        else -> "other"
                    }
                    client.addBreadcrumb(
                        "default",
                        "connectivity",
                        "Network transport: $transport",
                        "info",
                        linkedMapOf("transport" to transport),
                    )
                }
            }
            cm.registerNetworkCallback(request, cb)
            callback = cb
            SdkLogger.debug("Connectivity watcher registered")
        } catch (t: Throwable) {
            // Missing ACCESS_NETWORK_STATE or a restricted device — degrade.
            SdkLogger.debug("Connectivity watcher unavailable: ${t.message}")
        }
    }

    fun unregister() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            callback?.let { cm?.unregisterNetworkCallback(it) }
        } catch (ignored: Throwable) {
        } finally {
            callback = null
        }
    }
}
