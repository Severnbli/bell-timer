package com.belltimer.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** A firmware image on the phone, verified against its release manifest. */
data class StoredFirmware(val release: FirmwareRelease, val file: File)

/**
 * Keeps one downloaded firmware image in app storage. Downloading needs the
 * internet and installing needs the bell's Wi-Fi, so the two steps can happen
 * at different places and times.
 */
class FirmwareStore(context: Context) {

    private val dir = File(context.filesDir, "firmware")
    private val manifestFile = File(dir, "firmware.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** Where a download of [release] goes before [save] adopts it. */
    fun downloadTarget(release: FirmwareRelease): File = File(dir, "belltimer-${release.version}.bin")

    /** The stored image, or null when there is none or it no longer matches its checksum. */
    fun load(): StoredFirmware? {
        val stored = runCatching { json.decodeFromString<StoredManifest>(manifestFile.readText()) }.getOrNull()
            ?: return null
        val release = stored.toRelease()
        val file = downloadTarget(release)
        if (!file.exists() || file.length() != release.size || sha256(file) != release.sha256) {
            clear()
            return null
        }
        return StoredFirmware(release, file)
    }

    /** Adopts a verified download and drops any older image. */
    fun save(release: FirmwareRelease): StoredFirmware {
        val keep = downloadTarget(release)
        dir.listFiles()?.filter { it != keep && it != manifestFile }?.forEach { it.delete() }
        manifestFile.writeText(json.encodeToString(StoredManifest.from(release)))
        return StoredFirmware(release, keep)
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    @Serializable
    private data class StoredManifest(
        val version: String,
        val api: Int,
        val minApp: String? = null,
        val size: Long,
        val sha256: String,
        val md5: String,
        val binaryUrl: String,
        val pageUrl: String,
    ) {
        fun toRelease() = FirmwareRelease(version, api, minApp, size, sha256, md5, binaryUrl, pageUrl)

        companion object {
            fun from(r: FirmwareRelease) =
                StoredManifest(r.version, r.api, r.minApp, r.size, r.sha256, r.md5, r.binaryUrl, r.pageUrl)
        }
    }
}
