package com.example.downloader.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

sealed interface NetworkState {
    data object Online : NetworkState
    data object Offline : NetworkState
    data object Limited : NetworkState

    val isConnected: Boolean
        get() = this is Online
}

/**
 * Monitors system connectivity using Android ConnectivityManager NetworkCallbacks.
 * Accurately tracks Wi-Fi, Cellular, Ethernet, and Metered connection states.
 */
open class NetworkMonitor(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _networkState = MutableStateFlow<NetworkState>(getCurrentState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    val isOnlineFlow: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val state = evaluateNetwork(network)
                _networkState.value = state
                trySend(state.isConnected)
            }

            override fun onLost(network: Network) {
                val state = getCurrentState()
                _networkState.value = state
                trySend(state.isConnected)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val state = if (hasInternet && isValidated) {
                    NetworkState.Online
                } else if (hasInternet) {
                    NetworkState.Limited
                } else {
                    NetworkState.Offline
                }
                _networkState.value = state
                trySend(state.isConnected)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager?.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
            trySend(true)
        }

        awaitClose {
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (_: Exception) {}
        }
    }

    open fun isOnline(): Boolean {
        return getCurrentState().isConnected
    }

    open fun isWifiConnected(): Boolean {
        val activeNetwork = connectivityManager?.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun getCurrentState(): NetworkState {
        val cm = connectivityManager ?: return NetworkState.Online
        val activeNetwork = cm.activeNetwork ?: return NetworkState.Offline
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkState.Offline

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        return when {
            hasInternet && isValidated -> NetworkState.Online
            hasInternet -> NetworkState.Limited
            else -> NetworkState.Offline
        }
    }

    private fun evaluateNetwork(network: Network): NetworkState {
        val caps = connectivityManager?.getNetworkCapabilities(network) ?: return NetworkState.Offline
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        return when {
            hasInternet && isValidated -> NetworkState.Online
            hasInternet -> NetworkState.Limited
            else -> NetworkState.Offline
        }
    }
}
