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
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
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

    suspend fun getDevice(): DeviceConfigDto = decode(read("GET", "/device"))

    suspend fun saveDevice(config: DeviceConfigDto): ApiResponse =
        decode(read("POST", "/device", json.encodeToString(config.copy(error = false))))

    /**
     * Sends a firmware image to POST /update. The device checks [md5] before
     * it switches to the new image and restarts after the response.
     */
    suspend fun uploadFirmware(file: File, md5: String, onProgress: (Float) -> Unit): ApiResponse =
        withContext(Dispatchers.IO) {
            val network = findWifiNetwork() ?: throw ApiException.NoWifi()
            val baseUrl = settings.baseUrl.trim().trimEnd('/')
            val size = file.length()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("firmware", "firmware.bin", ProgressBody(file, FIRMWARE_MEDIA_TYPE, onProgress))
                .build()
            val request = Request.Builder()
                .url("$baseUrl/update?md5=$md5&size=$size")
                .post(body)
                .header("Accept", "application/json")
                .apply {
                    val token = settings.token.trim()
                    if (token.isNotEmpty()) header("X-Token", token)
                }
                .build()
            // Writing flash is slow: allow minutes instead of seconds.
            val client = clientFor(network).newBuilder()
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.MINUTES)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw ApiException.Http(response.code, parseErrorCode(text) ?: "http_${response.code}")
                    }
                    decode<ApiResponse>(text)
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: InterruptedIOException) {
                throw ApiException.Timeout(e)
            } catch (e: IOException) {
                throw ApiException.Network(e)
            }
        }

    fun hasWifiTransport(): Boolean = findWifiNetwork() != null

    private suspend fun read(method: String, path: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val network = findWifiNetwork() ?: throw ApiException.NoWifi()
            val baseUrl = settings.baseUrl.trim().trimEnd('/')
            // The firmware takes changes only as JSON, so a POST without
            // fields still sends an empty JSON object.
            val payload = body ?: if (method == "POST") "{}" else null
            val requestBody = payload?.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$baseUrl$path")
                .method(method, requestBody)
                .header("Accept", "application/json")
                .apply {
                    if (!payload.isNullOrEmpty()) {
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
        val FIRMWARE_MEDIA_TYPE = "application/octet-stream".toMediaType()
    }
}

/** Streams a file and reports how much of it was sent. */
private class ProgressBody(
    private val file: File,
    private val type: MediaType,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType = type

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length().coerceAtLeast(1)
        var sent = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                sent += read
                onProgress((sent.toFloat() / total).coerceIn(0f, 1f))
            }
        }
    }
}
