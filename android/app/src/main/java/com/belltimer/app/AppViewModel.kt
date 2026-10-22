package com.belltimer.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.belltimer.app.data.ApiClient
import com.belltimer.app.data.ApiException
import com.belltimer.app.data.AppSettings
import com.belltimer.app.data.BellEntry
import com.belltimer.app.data.DEFAULT_RING_SECONDS
import com.belltimer.app.data.DeviceStatus
import com.belltimer.app.data.MAX_RING_SECONDS
import com.belltimer.app.data.MIN_RING_SECONDS
import com.belltimer.app.data.SettingsStore
import com.belltimer.app.data.normalizeBaseUrl
import com.belltimer.app.data.sortedSchedule
import com.belltimer.app.data.toDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class ConnectionState { Connecting, Online, Offline }

data class UiState(
    val settings: AppSettings = AppSettings(),
    val connection: ConnectionState = ConnectionState.Offline,
    val status: DeviceStatus? = null,
    val lastError: String? = null,
    val schedule: List<BellEntry> = emptyList(),
    val scheduleDirty: Boolean = false,
    val scheduleLoaded: Boolean = false,
    val scheduleLoadAttempted: Boolean = false,
    val loadingSchedule: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val syncPromptVisible: Boolean = false,
    val ringSeconds: Int = DEFAULT_RING_SECONDS,
    val deviceRingDefault: Int? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SettingsStore(application)
    private val api = ApiClient(application)
    private val initialSettings = store.load()

    private val _state = MutableStateFlow(
        UiState(
            settings = initialSettings,
            ringSeconds = initialSettings.ringDefault,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var syncPromptShown = false

    init {
        api.updateSettings(initialSettings)
        viewModelScope.launch { refreshStatus() }
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            if (_state.value.connection == ConnectionState.Offline) {
                _state.update { it.copy(connection = ConnectionState.Connecting) }
            }
            while (isActive) {
                refreshStatus()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refreshNow() {
        viewModelScope.launch {
            if (_state.value.connection == ConnectionState.Offline) {
                _state.update { it.copy(connection = ConnectionState.Connecting) }
            }
            refreshStatus()
        }
    }

    suspend fun refreshStatus() {
        try {
            val domain = api.getStatus().toDomain()
            val snapshot = _state.value
            val shouldLoadSchedule =
                !snapshot.scheduleLoadAttempted && !snapshot.scheduleDirty
            val prompt = !syncPromptShown && needsSyncPrompt(domain)
            if (prompt) syncPromptShown = true

            _state.update {
                it.copy(
                    connection = ConnectionState.Online,
                    status = domain,
                    lastError = null,
                    deviceRingDefault = domain.ringDefault,
                    syncPromptVisible = it.syncPromptVisible || prompt,
                    scheduleLoadAttempted = it.scheduleLoadAttempted || shouldLoadSchedule,
                )
            }
            if (shouldLoadSchedule) {
                loadScheduleInternal(showSuccessMessage = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.update {
                it.copy(
                    connection = ConnectionState.Offline,
                    lastError = mapError(e),
                )
            }
        }
    }

    fun setMode(auto: Boolean) {
        if (_state.value.busy) return
        val target = if (auto) "auto" else "manual"
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val response = api.setMode(target)
                if (!response.ok) {
                    throw ApiException.Http(400, response.error ?: "invalid_mode")
                }
                _state.update {
                    it.copy(
                        busy = false,
                        lastError = null,
                        status = it.status?.copy(mode = response.mode ?: target),
                        message = getApplication<Application>().getString(
                            if (auto) R.string.mode_auto_set else R.string.mode_manual_set,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleActionError(e)
            }
        }
    }

    fun ring(seconds: Int) {
        if (_state.value.busy) return
        val safeSeconds = seconds.coerceIn(MIN_RING_SECONDS, MAX_RING_SECONDS)
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val response = api.ring(safeSeconds)
                if (!response.ok) {
                    throw ApiException.Http(400, response.error ?: "invalid_sec")
                }
                _state.update {
                    it.copy(
                        busy = false,
                        lastError = null,
                        status = it.status?.copy(
                            ringing = true,
                            ringLeft = response.ringLeft ?: safeSeconds,
                        ),
                        message = getApplication<Application>().getString(R.string.ring_started),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleActionError(e)
            }
        }
    }

    fun stopRing() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val response = api.stop()
                if (!response.ok) {
                    throw ApiException.Http(400, response.error ?: "invalid_request")
                }
                _state.update {
                    it.copy(
                        busy = false,
                        lastError = null,
                        status = it.status?.copy(ringing = false, ringLeft = 0),
                        message = getApplication<Application>().getString(R.string.ring_stopped),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleActionError(e)
            }
        }
    }

    fun setTimeFromPhone() {
        if (_state.value.busy) return
        val now = LocalDateTime.now().withNano(0)
        val payload = now.format(API_DATE_TIME_FORMAT)
        viewModelScope.launch {
            _state.update { it.copy(busy = true, syncPromptVisible = false) }
            try {
                val response = api.setTime(payload)
                if (!response.ok) {
                    throw ApiException.Http(400, response.error ?: "invalid_datetime")
                }
                _state.update {
                    it.copy(
                        busy = false,
                        lastError = null,
                        message = getApplication<Application>().getString(R.string.time_synced),
                    )
                }
                refreshStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleActionError(e)
            }
        }
    }

    fun confirmSyncTime() {
        setTimeFromPhone()
    }

    fun dismissSyncPrompt() {
        _state.update { it.copy(syncPromptVisible = false) }
    }

    fun loadSchedule() {
        viewModelScope.launch { loadScheduleInternal(showSuccessMessage = true) }
    }

    private suspend fun loadScheduleInternal(showSuccessMessage: Boolean) {
        if (_state.value.loadingSchedule) return
        _state.update { it.copy(loadingSchedule = true) }
        try {
            val entries = api.getSchedule().toDomain()
            _state.update {
                it.copy(
                    loadingSchedule = false,
                    schedule = entries,
                    scheduleDirty = false,
                    scheduleLoaded = true,
                    scheduleLoadAttempted = true,
                    lastError = null,
                    message = if (showSuccessMessage) {
                        getApplication<Application>().getString(R.string.schedule_loaded)
                    } else {
                        it.message
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.update {
                it.copy(
                    loadingSchedule = false,
                    scheduleLoadAttempted = true,
                    message = mapError(e),
                )
            }
        }
    }

    fun updateSchedule(entries: List<BellEntry>) {
        _state.update {
            it.copy(
                schedule = entries.sortedSchedule(),
                scheduleDirty = true,
                scheduleLoaded = true,
            )
        }
    }

    fun saveSchedule() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val entries = _state.value.schedule
                val response = api.saveSchedule(entries.map { it.toDto() })
                if (!response.ok) {
                    throw ApiException.Http(400, response.error ?: "invalid_schedule")
                }
                _state.update {
                    it.copy(
                        busy = false,
                        scheduleDirty = false,
                        scheduleLoaded = true,
                        lastError = null,
                        message = getApplication<Application>().getString(R.string.schedule_saved),
                        status = it.status?.copy(entries = entries.size),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleActionError(e)
            }
        }
    }

    fun saveSettings(baseUrl: String, token: String, ringDefault: Int) {
        val normalizedUrl = normalizeBaseUrl(baseUrl)
        if (normalizedUrl == null) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.settings_invalid_url))
            }
            return
        }
        if (ringDefault !in MIN_RING_SECONDS..MAX_RING_SECONDS) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.settings_invalid_duration))
            }
            return
        }

        val old = _state.value.settings
        val updated = AppSettings(
            baseUrl = normalizedUrl,
            token = token.trim(),
            ringDefault = ringDefault,
        )
        store.save(updated)
        api.updateSettings(updated)
        syncPromptShown = false

        val urlChanged = old.baseUrl != updated.baseUrl
        _state.update { current ->
            current.copy(
                settings = updated,
                ringSeconds = updated.ringDefault,
                busy = true,
                syncPromptVisible = false,
                message = null,
                status = if (urlChanged) null else current.status,
                schedule = if (urlChanged) emptyList() else current.schedule,
                scheduleDirty = if (urlChanged) false else current.scheduleDirty,
                scheduleLoaded = if (urlChanged) false else current.scheduleLoaded,
                scheduleLoadAttempted = if (urlChanged) false else current.scheduleLoadAttempted,
                connection = if (urlChanged) ConnectionState.Connecting else current.connection,
                lastError = if (urlChanged) null else current.lastError,
            )
        }

        viewModelScope.launch {
            var deviceSaved = false
            try {
                val response = api.saveSettings(ringDefault)
                deviceSaved = response.ok
                if (deviceSaved) {
                    _state.update { it.copy(deviceRingDefault = response.ringDefault ?: ringDefault) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                deviceSaved = false
            }
            refreshStatus()
            _state.update {
                it.copy(
                    busy = false,
                    message = getApplication<Application>().getString(
                        if (deviceSaved) R.string.settings_saved else R.string.settings_saved_local,
                    ),
                )
            }
        }
    }

    fun refreshDeviceSettings() {
        viewModelScope.launch {
            if (_state.value.connection != ConnectionState.Online) return@launch
            try {
                val settings = api.getSettings()
                _state.update { it.copy(deviceRingDefault = settings.ringDefault) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The status response already carries ring_default; ignore this
                // supplementary request failure.
            }
        }
    }

    fun setRingSeconds(seconds: Int) {
        _state.update { it.copy(ringSeconds = seconds.coerceIn(MIN_RING_SECONDS, MAX_RING_SECONDS)) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun handleActionError(e: Throwable) {
        if (e is CancellationException) throw e
        val offline = e is ApiException.NoWifi ||
            e is ApiException.Timeout ||
            e is ApiException.Network
        val text = mapError(e)
        _state.update {
            it.copy(
                busy = false,
                connection = if (offline) ConnectionState.Offline else it.connection,
                lastError = if (offline) text else it.lastError,
                message = text,
            )
        }
    }

    private fun needsSyncPrompt(status: DeviceStatus): Boolean {
        if (!status.rtcOk) return true
        val deviceTime = status.time ?: return true
        val drift = abs(ChronoUnit.SECONDS.between(deviceTime, LocalDateTime.now()))
        return drift > MAX_CLOCK_DRIFT_SECONDS
    }

    private fun mapError(e: Throwable): String {
        val context = getApplication<Application>()
        return when (e) {
            is ApiException.NoWifi -> context.getString(R.string.error_no_wifi)
            is ApiException.Timeout -> context.getString(R.string.error_timeout)
            is ApiException.Network -> context.getString(
                R.string.error_network,
                e.cause?.message ?: e.message.orEmpty(),
            )
            is ApiException.InvalidJson -> context.getString(R.string.error_invalid_json)
            is ApiException.Http -> when (e.errorCode) {
                "unauthorized" -> context.getString(R.string.error_unauthorized)
                "invalid_sec" -> context.getString(R.string.error_invalid_sec)
                "invalid_mode" -> context.getString(R.string.error_invalid_mode)
                "invalid_datetime" -> context.getString(R.string.error_invalid_datetime)
                "invalid_schedule" -> context.getString(R.string.error_invalid_schedule)
                "invalid_request" -> context.getString(R.string.error_invalid_request)
                "too_many_entries" -> context.getString(R.string.error_too_many_entries)
                "invalid_ring_default" -> context.getString(R.string.error_invalid_ring_default)
                "invalid_json" -> context.getString(R.string.error_invalid_json)
                "storage_error" -> context.getString(R.string.error_storage_device)
                "rtc_not_found" -> context.getString(R.string.error_rtc_device)
                "not_found" -> context.getString(R.string.error_not_found)
                else -> if (e.code >= 500) {
                    context.getString(R.string.error_http, e.code)
                } else {
                    context.getString(R.string.error_device_generic, e.errorCode)
                }
            }
            else -> context.getString(
                R.string.error_unexpected,
                e.message ?: e.javaClass.simpleName,
            )
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val MAX_CLOCK_DRIFT_SECONDS = 5L
        val API_DATE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
