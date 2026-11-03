package com.belltimer.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** The newest firmware release on GitHub and its manifest (firmware.json). */
data class FirmwareRelease(
    val version: String,
    val api: Int,
    /** Oldest app that can drive this firmware. */
    val minApp: String?,
    val size: Long,
    val sha256: String,
    val md5: String,
    val binaryUrl: String,
    val pageUrl: String,
)

/** The newest app release on GitHub and its manifest (app.json), if any. */
data class AppRelease(
    val version: String,
    /** Oldest firmware this app can drive; null for releases before app.json. */
    val minFirmware: String?,
    val pageUrl: String,
)

data class LatestReleases(val app: AppRelease?, val firmware: FirmwareRelease?)

sealed class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Neither a validated network nor mobile data could be reached. */
    class NoInternet : UpdateException("no_internet")
    class Http(val code: Int) : UpdateException("http_$code")
    class Network(cause: Throwable) : UpdateException("network", cause)
    class InvalidManifest : UpdateException("invalid_manifest")
    class ChecksumMismatch : UpdateException("checksum_mismatch")
}

/**
 * Talks to GitHub to find and download releases. The phone is often joined to
 * the bell's access point, which has no internet, so every request is bound
 * to a network that really has internet: a validated one when there is any,
 * otherwise mobile data, which Android brings up for this app on request.
 */
class UpdateClient(context: Context) {

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    private val userAgent = "BellTimer-Android"

    /** Lists the releases and picks the newest stable app and firmware. */
    suspend fun latest(): LatestReleases = withInternet { client ->
        val text = get(client, "$API_BASE/releases?per_page=50").use { it.body?.string().orEmpty() }
        val releases = try {
            json.decodeFromString<List<ReleaseDto>>(text)
        } catch (e: Exception) {
            throw UpdateException.InvalidManifest()
        }
        val stable = releases.filter { !it.draft && !it.prerelease }

        val firmwareRelease = stable
            .filter { it.tagName.startsWith(FIRMWARE_TAG_PREFIX) }
            .maxWithOrNull { a, b -> compareVersions(a.tagName.removePrefix(FIRMWARE_TAG_PREFIX), b.tagName.removePrefix(FIRMWARE_TAG_PREFIX)) }
        val firmware = firmwareRelease?.let { release ->
            val manifestUrl = release.assets.firstOrNull { it.name == FIRMWARE_MANIFEST }?.url
                ?: return@let null
            val manifest = get(client, manifestUrl).use { response ->
                runCatching { json.decodeFromString<FirmwareManifestDto>(response.body?.string().orEmpty()) }
                    .getOrNull()
            } ?: throw UpdateException.InvalidManifest()
            val binary = release.assets.firstOrNull { it.name == manifest.file }
                ?: throw UpdateException.InvalidManifest()
            if (!manifest.isComplete) throw UpdateException.InvalidManifest()
            FirmwareRelease(
                version = manifest.version,
                api = manifest.api,
                minApp = manifest.minApp,
                size = manifest.size,
                sha256 = manifest.sha256.lowercase(),
                md5 = manifest.md5.lowercase(),
                binaryUrl = binary.url,
                pageUrl = release.htmlUrl,
            )
        }

        val appRelease = stable
            .filter { APP_TAG.matches(it.tagName) }
            .maxWithOrNull { a, b -> compareVersions(a.tagName, b.tagName) }
        val app = appRelease?.let { release ->
            val manifest = release.assets.firstOrNull { it.name == APP_MANIFEST }?.url?.let { url ->
                get(client, url).use { response ->
                    runCatching { json.decodeFromString<AppManifestDto>(response.body?.string().orEmpty()) }
                        .getOrNull()
                }
            }
            AppRelease(
                version = manifest?.version ?: release.tagName.removePrefix("v"),
                minFirmware = manifest?.minFirmware,
                pageUrl = release.htmlUrl,
            )
        }
        LatestReleases(app = app, firmware = firmware)
    }

    /**
     * Downloads [release] into [target] and verifies its size and SHA-256
     * against the manifest. A file that does not match is deleted.
     */
    suspend fun download(release: FirmwareRelease, target: File, onProgress: (Float) -> Unit) {
        withInternet { client ->
            target.parentFile?.mkdirs()
            val partial = File(target.path + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            get(client, release.binaryUrl).use { response ->
                val body = response.body ?: throw UpdateException.InvalidManifest()
                val total = release.size.coerceAtLeast(1)
                var written = 0L
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                            if (written > release.size) {
                                partial.delete()
                                throw UpdateException.ChecksumMismatch()
                            }
                            onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                val sha256 = digest.digest().toHex()
                if (written != release.size || sha256 != release.sha256) {
                    partial.delete()
                    throw UpdateException.ChecksumMismatch()
                }
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) throw UpdateException.Network(IOException("rename failed"))
        }
    }

    private fun get(client: OkHttpClient, url: String): okhttp3.Response {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", if (url.startsWith(API_BASE)) "application/vnd.github+json" else "*/*")
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw UpdateException.Network(e)
        }
        if (!response.isSuccessful) {
            response.close()
            throw UpdateException.Http(response.code)
        }
        return response
    }

    /** Runs [block] with a client bound to a network that has internet. */
    private suspend fun <T> withInternet(block: suspend (OkHttpClient) -> T): T = withContext(Dispatchers.IO) {
        val manager = connectivityManager ?: return@withContext block(baseClient)
        val validated = findValidatedNetwork(manager)
        if (validated != null) return@withContext block(clientFor(validated))

        // Joined to the bell only: ask Android for mobile data and keep the
        // request open for as long as the transfer runs.
        val (network, callback) = requestCellular(manager) ?: throw UpdateException.NoInternet()
        try {
            block(clientFor(network))
        } finally {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }

    private fun findValidatedNetwork(manager: ConnectivityManager): Network? {
        fun hasInternet(network: Network): Boolean {
            val caps = manager.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        manager.activeNetwork?.takeIf(::hasInternet)?.let { return it }
        @Suppress("DEPRECATION")
        return manager.allNetworks.firstOrNull(::hasInternet)
    }

    /**
     * Requests mobile data. Returns the network and the callback that holds
     * the request (the caller unregisters it), or null when there is none.
     */
    private suspend fun requestCellular(
        manager: ConnectivityManager,
    ): Pair<Network, ConnectivityManager.NetworkCallback>? = suspendCancellableCoroutine { continuation ->
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (continuation.isActive) continuation.resume(network to this)
            }

            override fun onUnavailable() {
                // A request that timed out is released by the system.
                if (continuation.isActive) continuation.resume(null)
            }
        }
        try {
            manager.requestNetwork(request, callback, CELLULAR_TIMEOUT_MS)
        } catch (_: RuntimeException) {
            // No permission or too many requests: behave as if there were no mobile data.
            if (continuation.isActive) continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }

    private fun clientFor(network: Network): OkHttpClient =
        baseClient.newBuilder().socketFactory(network.socketFactory).build()

    private companion object {
        const val API_BASE = "https://api.github.com/repos/Severnbli/bell-timer"
        const val FIRMWARE_TAG_PREFIX = "firmware-v"
        const val FIRMWARE_MANIFEST = "firmware.json"
        const val APP_MANIFEST = "app.json"
        val APP_TAG = Regex("""^v\d+\.\d+\.\d+$""")
        const val CELLULAR_TIMEOUT_MS = 10_000
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
private data class AssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
)

@Serializable
private data class FirmwareManifestDto(
    val version: String = "",
    val api: Int = 0,
    @SerialName("min_app") val minApp: String? = null,
    val file: String = "",
    val size: Long = 0,
    val sha256: String = "",
    val md5: String = "",
) {
    val isComplete: Boolean
        get() = version.isNotBlank() && file.isNotBlank() && size > 0 &&
            sha256.length == 64 && md5.length == 32
}

@Serializable
private data class AppManifestDto(
    val version: String? = null,
    @SerialName("min_firmware") val minFirmware: String? = null,
)
