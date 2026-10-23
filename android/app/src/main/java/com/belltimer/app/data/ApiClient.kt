package com.belltimer.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoWifi : ApiException("no_wifi")
    class Timeout(cause: Throwable? = null) : ApiException("timeout", cause)
    class Network(cause: Throwable? = null) : ApiException("network", cause)
    class InvalidJson : ApiException("invalid_json")
    class Http(val code: Int, val errorCode: String) : ApiException(errorCode)
}

class ApiClient(context: Context) {

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    private val networkClients = ConcurrentHashMap<Network, OkHttpClient>()
    private val wifiNetworks: MutableSet<Network> =
        Collections.synchronizedSet(mutableSetOf<Network>())

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetworks.add(network)
        }

        override fun onLost(network: Network) {
            wifiNetworks.remove(network)
        }
    }

    @Volatile
    private var settings: AppSettings = AppSettings()

    init {
        val manager = connectivityManager
        if (manager != null) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            runCatching { manager.registerNetworkCallback(request, wifiCallback) }
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        settings = newSettings
    }

    suspend fun getStatus(): StatusDto = decode(read("GET", "/status"))

    suspend fun ring(seconds: Int): ApiResponse = decode(
        read("POST", "/ring", json.encodeToString(RingRequest(seconds))),
    )

    suspend fun stop(): ApiResponse = decode(read("POST", "/stop"))

    suspend fun setMode(mode: String): ApiResponse =
        decode(read("POST", "/mode", json.encodeToString(ModeRequest(mode))))

    suspend fun setTime(localDateTime: String): ApiResponse =
        decode(read("POST", "/time", json.encodeToString(TimeRequest(localDateTime))))

    suspend fun getSchedule(): ScheduleDto = decode(read("GET", "/schedule"))

    suspend fun saveSchedule(schedule: ScheduleDto): ApiResponse = decode(
        read("POST", "/schedule", json.encodeToString(schedule)),
    )

    suspend fun getSettings(): SettingsDto = decode(read("GET", "/settings"))

    suspend fun saveSettings(ringDefault: Int): ApiResponse =
        decode(read("POST", "/settings", json.encodeToString(SettingsRequest(ringDefault))))

    suspend fun factoryReset(network: Boolean): ApiResponse =
        decode(read("POST", "/reset", json.encodeToString(ResetRequest(network))))

    suspend fun getNetwork(): NetworkDto = decode(read("GET", "/network"))

    suspend fun saveNetwork(request: NetworkUpdateDto): ApiResponse =
        decode(read("POST", "/network", json.encodeToString(request)))

    fun hasWifiTransport(): Boolean = findWifiNetwork() != null

    private suspend fun read(method: String, path: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val network = findWifiNetwork() ?: throw ApiException.NoWifi()
            val baseUrl = settings.baseUrl.trim().trimEnd('/')
            val requestBody = body?.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$baseUrl$path")
                .method(method, requestBody)
                .header("Accept", "application/json")
                .apply {
                    if (!body.isNullOrEmpty()) {
                        header("Content-Type", "application/json; charset=utf-8")
                    }
                    val token = settings.token.trim()
                    if (token.isNotEmpty()) {
                        header("X-Token", token)
                    }
                }
                .build()

            val client = clientFor(network)
            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val errorCode = parseErrorCode(text) ?: "http_${response.code}"
                        throw ApiException.Http(response.code, errorCode)
                    }
                    if (text.isBlank()) throw ApiException.InvalidJson()
                    text
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: InterruptedIOException) {
                throw ApiException.Timeout(e)
            } catch (e: IOException) {
                throw ApiException.Network(e)
            }
        }

    private fun findWifiNetwork(): Network? {
        val manager = connectivityManager ?: return null
        val known = synchronized(wifiNetworks) {
            wifiNetworks.firstOrNull { isWifi(manager, it) }
        }
        if (known != null) return known

        // Fallback for the very first request before the network callback has
        // fired. allNetworks is deprecated but is the only synchronous way to
        // enumerate a non-default (Wi-Fi) network.
        @Suppress("DEPRECATION")
        val networks = manager.allNetworks
        return networks.firstOrNull { isWifi(manager, it) }
    }

    private fun isWifi(manager: ConnectivityManager, network: Network): Boolean {
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun clientFor(network: Network): OkHttpClient =
        networkClients.getOrPut(network) {
            baseClient.newBuilder()
                .socketFactory(network.socketFactory)
                .build()
        }

    private fun parseErrorCode(body: String): String? =
        runCatching { json.decodeFromString<ApiResponse>(body).error }.getOrNull()

    private inline fun <reified T> decode(text: String): T =
        try {
            json.decodeFromString<T>(text)
        } catch (_: SerializationException) {
            throw ApiException.InvalidJson()
        } catch (_: IllegalArgumentException) {
            throw ApiException.InvalidJson()
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
