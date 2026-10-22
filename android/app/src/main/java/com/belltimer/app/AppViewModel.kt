package com.belltimer.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.belltimer.app.data.API_SPECIAL_DAYS
import com.belltimer.app.data.ApiClient
import com.belltimer.app.data.ApiException
import com.belltimer.app.data.AppSettings
import com.belltimer.app.data.BellSchedule
import com.belltimer.app.data.DEFAULT_RING_SECONDS
import com.belltimer.app.data.DayOverride
import com.belltimer.app.data.DeviceStatus
import com.belltimer.app.data.MAX_DATE_OVERRIDES
import com.belltimer.app.data.MAX_RING_SECONDS
import com.belltimer.app.data.MAX_SCHEDULE_ENTRIES
import com.belltimer.app.data.MAX_SCHEDULE_GROUPS
import com.belltimer.app.data.MIN_RING_SECONDS
import com.belltimer.app.data.ScheduleCodec
import com.belltimer.app.data.ScheduleIssue
import com.belltimer.app.data.SettingsStore
import com.belltimer.app.data.canAddOverride
import com.belltimer.app.data.normalizeBaseUrl
import com.belltimer.app.data.toDomain
import com.belltimer.app.data.toDto
import com.belltimer.app.data.validate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class ConnectionState { Connecting, Online, Offline }

data class UiState(
    val settings: AppSettings = AppSettings(),
    val connection: ConnectionState = ConnectionState.Offline,
    val status: DeviceStatus? = null,
    val lastError: String? = null,
    val schedule: BellSchedule = BellSchedule(),
    val scheduleDirty: Boolean = false,
    val scheduleLoaded: Boolean = false,
    val scheduleLoadAttempted: Boolean = false,
    val loadingSchedule: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val undoAvailable: Boolean = false,
    val scheduleIssue: ScheduleIssue? = null,
    val ringSeconds: Int = DEFAULT_RING_SECONDS,
    val deviceRingDefault: Int? = null,
    /** API level reported by the device; 2 until the first status response. */
    val apiVersion: Int = 2,
) {
    val supportsSpecialDays: Boolean get() = apiVersion >= API_SPECIAL_DAYS
}

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
    private var undoSnapshot: BellSchedule? = null

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

            _state.update {
                it.copy(
                    connection = ConnectionState.Online,
                    status = domain,
                    lastError = null,
                    deviceRingDefault = domain.ringDefault,
                    apiVersion = domain.api,
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
            _state.update { it.copy(busy = true) }
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

    fun loadSchedule() {
        viewModelScope.launch { loadScheduleInternal(showSuccessMessage = true) }
    }

    /** Drops local edits and reloads the schedule from the device. */
    fun discardScheduleChanges() {
        viewModelScope.launch { loadScheduleInternal(showSuccessMessage = false) }
    }

    private suspend fun loadScheduleInternal(showSuccessMessage: Boolean) {
        if (_state.value.loadingSchedule) return
        _state.update { it.copy(loadingSchedule = true) }
        try {
            val schedule = api.getSchedule().toDomain()
            undoSnapshot = null
            _state.update {
                it.copy(
                    loadingSchedule = false,
                    schedule = schedule,
                    scheduleDirty = false,
                    scheduleLoaded = true,
                    scheduleLoadAttempted = true,
                    undoAvailable = false,
                    scheduleIssue = null,
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

    /**
     * Replaces the in-memory schedule. Every edit funnels through here, so
     * [undoable] edits can be rolled back with [undoLastScheduleChange].
     */
    fun updateSchedule(schedule: BellSchedule, undoable: Boolean = false) {
        val previous = _state.value.schedule
        if (undoable && previous != schedule) {
            undoSnapshot = previous
            _state.update {
                it.copy(
                    schedule = schedule,
                    scheduleDirty = true,
                    scheduleLoaded = true,
                    undoAvailable = true,
                )
            }
        } else {
            _state.update {
                it.copy(
                    schedule = schedule,
                    scheduleDirty = true,
                    scheduleLoaded = true,
                )
            }
        }
    }

    fun undoLastScheduleChange() {
        val snapshot = undoSnapshot ?: return
        undoSnapshot = null
        _state.update {
            it.copy(
                schedule = snapshot,
                scheduleDirty = true,
                scheduleLoaded = true,
                undoAvailable = false,
            )
        }
    }

    /** Creates one override per date, skipping dates that already have one. */
    fun addOverrides(template: DayOverride, dates: List<LocalDate>) {
        var current = _state.value.schedule
        var added = 0
        var limited = false
        for (date in dates) {
            if (current.overrideFor(date) != null) continue
            if (!current.canAddOverride()) {
                limited = true
                continue
            }
            current = current.withOverride(template.copy(date = date))
            added++
        }
        if (added > 0) updateSchedule(current, undoable = true)
        val context = getApplication<Application>()
        val message = if (added == 0 && limited) {
            context.getString(R.string.day_repeat_limit, MAX_DATE_OVERRIDES)
        } else {
            context.getString(R.string.day_repeat_created, added)
        }
        _state.update { it.copy(message = message) }
    }

    /** Validates and sends the whole schedule, then verifies the device copy. */
    fun sendSchedule() {
        if (_state.value.busy) return
        val current = _state.value
        val issue = current.schedule.validate(current.apiVersion)
        if (issue != null) {
            _state.update { it.copy(scheduleIssue = issue, message = describeIssue(issue)) }
            return
        }
        val expected = current.schedule.totalEntries
        viewModelScope.launch {
            _state.update { it.copy(busy = true, scheduleIssue = null) }
            try {
                val response = api.saveSchedule(current.schedule.toDto())
                if (!response.ok) {
                    throw ApiException.Http(400, response.error ?: "invalid_schedule")
                }
                val reloaded = api.getSchedule().toDomain()
                val actual = reloaded.totalEntries
                undoSnapshot = null
                _state.update {
                    it.copy(
                        busy = false,
                        lastError = null,
                        // The device is the authority: adopt what it stored and
                        // only report the count when it differs from the payload.
                        schedule = reloaded,
                        scheduleDirty = false,
                        scheduleLoaded = true,
                        undoAvailable = false,
                        scheduleIssue = null,
                        status = it.status?.copy(entries = actual),
                        message = getApplication<Application>().getString(
                            if (actual == expected) {
                                R.string.schedule_sent_verified
                            } else {
                                R.string.schedule_sent_mismatch
                            },
                            actual,
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

    fun consumeScheduleIssue() {
        _state.update { it.copy(scheduleIssue = null) }
    }

    /** Returns the current schedule as pretty-printed JSON, or null when empty. */
    fun exportScheduleText(): String? {
        val schedule = _state.value.schedule
        if (schedule.isEmpty) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.schedule_export_empty))
            }
            return null
        }
        return ScheduleCodec.encode(schedule)
    }

    fun reportExportResult(success: Boolean) {
        _state.update {
            it.copy(
                message = getApplication<Application>().getString(
                    if (success) R.string.schedule_export_success else R.string.schedule_export_error,
                ),
            )
        }
    }

    fun importScheduleText(text: String) {
        val imported = try {
            ScheduleCodec.decode(text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.schedule_import_error))
            }
            return
        }
        if (!withinDeviceLimits(imported)) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.schedule_too_large))
            }
            return
        }
        undoSnapshot = _state.value.schedule
        _state.update {
            it.copy(
                schedule = imported,
                scheduleDirty = true,
                scheduleLoaded = true,
                undoAvailable = true,
                scheduleIssue = null,
                message = getApplication<Application>().getString(
                    R.string.schedule_import_success,
                    imported.totalEntries,
                ),
            )
        }
    }

    private fun withinDeviceLimits(schedule: BellSchedule): Boolean =
        schedule.groups.size <= MAX_SCHEDULE_GROUPS &&
            schedule.totalEntries <= MAX_SCHEDULE_ENTRIES &&
            schedule.overrides.size <= MAX_DATE_OVERRIDES

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

        val urlChanged = old.baseUrl != updated.baseUrl
        _state.update { current ->
            current.copy(
                settings = updated,
                ringSeconds = updated.ringDefault,
                busy = true,
                message = null,
                status = if (urlChanged) null else current.status,
                schedule = if (urlChanged) BellSchedule() else current.schedule,
                scheduleDirty = if (urlChanged) false else current.scheduleDirty,
                scheduleLoaded = if (urlChanged) false else current.scheduleLoaded,
                scheduleLoadAttempted = if (urlChanged) false else current.scheduleLoadAttempted,
                scheduleIssue = if (urlChanged) null else current.scheduleIssue,
                undoAvailable = if (urlChanged) false else current.undoAvailable,
                connection = if (urlChanged) ConnectionState.Connecting else current.connection,
                lastError = if (urlChanged) null else current.lastError,
            )
        }
        if (urlChanged) undoSnapshot = null

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

    fun setRingSeconds(seconds: Int) {
        _state.update { it.copy(ringSeconds = seconds.coerceIn(MIN_RING_SECONDS, MAX_RING_SECONDS)) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun reportMessage(message: String) {
        _state.update { it.copy(message = message) }
    }

    private fun describeIssue(issue: ScheduleIssue): String {
        val context = getApplication<Application>()
        return when (issue) {
            is ScheduleIssue.AutoWindowInvalid -> context.getString(R.string.schedule_auto_invalid)
            is ScheduleIssue.ScheduleWithoutDays -> context.getString(R.string.schedule_without_days)
            is ScheduleIssue.OverlappingWeekdays -> context.getString(R.string.schedule_overlap_error)
            is ScheduleIssue.TooManyEntries -> context.getString(R.string.error_too_many_entries)
            is ScheduleIssue.TooManyGroups -> context.getString(R.string.error_too_many_groups)
            is ScheduleIssue.TooManyOverrides -> context.getString(R.string.error_too_many_overrides)
            is ScheduleIssue.FirmwareTooOld -> context.getString(R.string.schedule_needs_new_firmware)
        }
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
                "too_many_groups" -> context.getString(R.string.error_too_many_groups)
                "too_many_overrides" -> context.getString(R.string.error_too_many_overrides)
                "too_many_exceptions" -> context.getString(R.string.error_too_many_exceptions)
                "invalid_ring_default" -> context.getString(R.string.error_invalid_ring_default)
                "invalid_json" -> context.getString(R.string.error_invalid_json)
                "storage_error" -> context.getString(R.string.error_storage_device)
                "rtc_not_found" -> context.getString(R.string.error_rtc_device)
                "not_found" -> context.getString(R.string.error_not_found)
                "invalid_date" -> context.getString(R.string.error_invalid_date)
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
        val API_DATE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
