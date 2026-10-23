package com.belltimer.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.belltimer.app.data.ApiClient
import com.belltimer.app.data.ApiException
import com.belltimer.app.data.AppSettings
import com.belltimer.app.data.BellSchedule
import com.belltimer.app.data.BuiltInTemplates
import com.belltimer.app.data.LessonTemplate
import com.belltimer.app.data.TemplateStore
import com.belltimer.app.data.DEFAULT_RING_SECONDS
import com.belltimer.app.data.DeviceStatus
import com.belltimer.app.data.MAX_RING_SECONDS
import com.belltimer.app.data.NetworkDto
import com.belltimer.app.data.NetworkUpdateDto
import com.belltimer.app.data.MIN_RING_SECONDS
import com.belltimer.app.data.ScheduleCodec
import com.belltimer.app.data.ScheduleIssue
import com.belltimer.app.data.SettingsStore
import com.belltimer.app.data.normalizeBaseUrl
import com.belltimer.app.data.toDomain
import com.belltimer.app.data.toDto
import com.belltimer.app.data.validate
import com.belltimer.app.data.withinDeviceLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

enum class ConnectionState { Connecting, Online, Offline }

data class UiState(
    val settings: AppSettings = AppSettings(),
    val connection: ConnectionState = ConnectionState.Offline,
    val status: DeviceStatus? = null,
    /** Phone clock, in epoch milliseconds, when [status] arrived. */
    val statusReceivedAtMs: Long = 0L,
    /** Device clock minus phone clock, in seconds; null without a device time. */
    val driftSeconds: Long? = null,
    val lastError: String? = null,
    val schedule: BellSchedule = BellSchedule(),
    val scheduleDirty: Boolean = false,
    val scheduleLoaded: Boolean = false,
    val loadingSchedule: Boolean = false,
    val busy: Boolean = false,
    val syncing: Boolean = false,
    val message: String? = null,
    val undoAvailable: Boolean = false,
    val scheduleIssue: ScheduleIssue? = null,
    /** Access point settings read from the device; null until loaded. */
    val network: NetworkDto? = null,
) {
    val online: Boolean get() = connection == ConnectionState.Online

    /** The device clock, advanced locally between two status polls. */
    fun deviceNow(nowMs: Long = System.currentTimeMillis()): LocalDateTime? {
        val time = status?.time ?: return null
        val elapsed = (nowMs - statusReceivedAtMs).coerceIn(0L, STALE_STATUS_MS)
        return time.plusNanos(elapsed * 1_000_000)
    }

    val clockInSync: Boolean
        get() = driftSeconds?.let { abs(it) <= SYNC_TOLERANCE_SEC } == true

    private companion object {
        const val STALE_STATUS_MS = 10_000L
    }
}

/** A difference of up to this many seconds is polling noise, not drift. */
const val SYNC_TOLERANCE_SEC = 2L

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SettingsStore(application)
    private val api = ApiClient(application)
    private val initialSettings = store.load()

    private val _state = MutableStateFlow(UiState(settings = initialSettings))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val templateStore = TemplateStore(application)
    private val customTemplates = MutableStateFlow(templateStore.load())

    /** Built-in templates first, then the user's own in creation order. */
    val templates: StateFlow<List<LessonTemplate>> = customTemplates
        .map { BuiltInTemplates.all + it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BuiltInTemplates.all + customTemplates.value)

    private var pollJob: Job? = null
    private var undoSnapshot: BellSchedule? = null
    private var scheduleLoadAttempted = false
    private var lastAutoSyncAt = 0L
    private var savingSettings = false

    init {
        api.updateSettings(initialSettings)
    }

    /** Messages follow the language chosen in the app, not only the system one. */
    private fun text(id: Int, vararg args: Any): String =
        AppLocale.wrap(getApplication()).getString(id, *args)

    // ------------------------------ Polling ------------------------------

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

    private suspend fun refreshStatus() {
        try {
            applyStatus(api.getStatus().toDomain())
            if (!scheduleLoadAttempted && !_state.value.scheduleDirty) {
                scheduleLoadAttempted = true
                loadScheduleInternal(showSuccessMessage = false)
            }
            maybeAutoSyncTime()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.update {
                it.copy(connection = ConnectionState.Offline, lastError = mapError(e))
            }
        }
    }

    private fun applyStatus(status: DeviceStatus) {
        val receivedAt = System.currentTimeMillis()
        val phoneNow = LocalDateTime.now()
        adoptDeviceRingDefault(status.ringDefault)
        _state.update {
            it.copy(
                connection = ConnectionState.Online,
                status = status,
                statusReceivedAtMs = receivedAt,
                driftSeconds = status.time?.let { time -> Duration.between(phoneNow, time).seconds },
                lastError = null,
            )
        }
    }

    /**
     * The device is the authority on the ring duration: another phone, the
     * web page or a factory reset may have changed it. Skipped while this app
     * is saving its own value, so a poll cannot undo the user's change.
     */
    private fun adoptDeviceRingDefault(seconds: Int) {
        if (savingSettings || seconds !in MIN_RING_SECONDS..MAX_RING_SECONDS) return
        val current = _state.value.settings
        if (current.ringDefault == seconds) return
        val updated = current.copy(ringDefault = seconds)
        store.save(updated)
        api.updateSettings(updated)
        _state.update { it.copy(settings = updated) }
    }

    // ----------------------------- Controls ------------------------------

    fun setMode(auto: Boolean) = action {
        val target = if (auto) "auto" else "manual"
        val response = api.setMode(target)
        if (!response.ok) throw ApiException.Http(400, response.error ?: "invalid_mode")
        _state.update {
            it.copy(
                status = it.status?.copy(mode = response.mode ?: target),
                message = text(if (auto) R.string.mode_auto_set else R.string.mode_manual_set),
            )
        }
    }

    fun ring() = action {
        val seconds = _state.value.settings.ringDefault.coerceIn(MIN_RING_SECONDS, MAX_RING_SECONDS)
        val response = api.ring(seconds)
        if (!response.ok) throw ApiException.Http(400, response.error ?: "invalid_sec")
        _state.update {
            it.copy(status = it.status?.copy(ringing = true, ringLeft = response.ringLeft ?: seconds))
        }
    }

    fun stopRing() = action {
        val response = api.stop()
        if (!response.ok) throw ApiException.Http(400, response.error ?: "invalid_request")
        _state.update { it.copy(status = it.status?.copy(ringing = false, ringLeft = 0)) }
    }

    /** Runs a device request with the busy flag set and errors reported. */
    private fun action(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
                _state.update { it.copy(busy = false, lastError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleActionError(e)
            }
        }
    }

    // ----------------------------- Time sync -----------------------------

    /** Sets the device clock from the phone, on the user's request. */
    fun syncTime() {
        if (_state.value.syncing) return
        viewModelScope.launch {
            val before = _state.value.driftSeconds
            try {
                pushPhoneTime()
                _state.update {
                    it.copy(
                        message = if (before != null && abs(before) > SYNC_TOLERANCE_SEC) {
                            text(R.string.sync_done_fixed, formatDrift(before))
                        } else {
                            text(R.string.sync_done)
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleActionError(e)
            }
        }
    }

    /**
     * Keeps the device clock in step with the phone without any user action,
     * when enabled in the settings. Syncs when the device has no valid time or
     * drifts by more than a minute, at most once every ten minutes.
     */
    private suspend fun maybeAutoSyncTime() {
        val current = _state.value
        val status = current.status ?: return
        if (!current.settings.autoSyncTime || current.syncing || current.busy || !status.rtcOk) return
        val drift = current.driftSeconds
        val needsSync = status.time == null || (drift != null && abs(drift) > AUTO_SYNC_DRIFT_SEC)
        if (!needsSync) return
        if (System.currentTimeMillis() - lastAutoSyncAt < AUTO_SYNC_INTERVAL_MS) return
        lastAutoSyncAt = System.currentTimeMillis()
        try {
            pushPhoneTime()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Silent: the next poll retries once the interval has passed.
        }
    }

    /**
     * Waits for the phone clock to cross a whole second and sends that second,
     * so the device ends up within network latency of the phone instead of
     * up to a second behind.
     */
    private suspend fun pushPhoneTime() {
        _state.update { it.copy(syncing = true) }
        try {
            val waitMs = 1000L - System.currentTimeMillis() % 1000L
            delay(waitMs)
            val payload = LocalDateTime.now().withNano(0).format(API_DATE_TIME_FORMAT)
            val response = api.setTime(payload)
            if (!response.ok) throw ApiException.Http(400, response.error ?: "invalid_datetime")
            applyStatus(api.getStatus().toDomain())
        } finally {
            _state.update { it.copy(syncing = false) }
        }
    }

    // ------------------------------ Schedule -----------------------------

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
                    undoAvailable = false,
                    scheduleIssue = null,
                    message = if (showSuccessMessage) text(R.string.schedule_loaded) else it.message,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.update { it.copy(loadingSchedule = false, message = mapError(e)) }
        }
    }

    /**
     * Replaces the in-memory schedule. Every edit funnels through here, so
     * [undoable] edits can be rolled back with [undoLastScheduleChange].
     */
    fun updateSchedule(schedule: BellSchedule, undoable: Boolean = false, message: String? = null) {
        val previous = _state.value.schedule
        if (previous == schedule) return
        if (undoable) undoSnapshot = previous
        _state.update {
            it.copy(
                schedule = schedule,
                scheduleDirty = true,
                scheduleLoaded = true,
                undoAvailable = if (undoable) true else it.undoAvailable,
                message = message ?: it.message,
            )
        }
    }

    fun undoLastScheduleChange() {
        val snapshot = undoSnapshot ?: return
        undoSnapshot = null
        _state.update { it.copy(schedule = snapshot, scheduleDirty = true, undoAvailable = false) }
    }

    /** Validates and sends the whole schedule, then verifies the device copy. */
    fun sendSchedule() {
        val current = _state.value
        val issue = current.schedule.validate(current.status?.api ?: 0)
        if (issue != null) {
            _state.update { it.copy(scheduleIssue = issue, message = describeIssue(issue)) }
            return
        }
        val expected = current.schedule.totalModules
        action {
            val response = api.saveSchedule(current.schedule.toDto())
            if (!response.ok) throw ApiException.Http(400, response.error ?: "invalid_schedule")
            val reloaded = api.getSchedule().toDomain()
            val actual = reloaded.totalModules
            undoSnapshot = null
            _state.update {
                it.copy(
                    // The device is the authority: adopt what it stored.
                    schedule = reloaded,
                    scheduleDirty = false,
                    scheduleLoaded = true,
                    undoAvailable = false,
                    scheduleIssue = null,
                    message = text(
                        if (actual == expected) R.string.schedule_sent else R.string.schedule_sent_mismatch,
                        actual,
                    ),
                )
            }
            runCatching { applyStatus(api.getStatus().toDomain()) }
        }
    }

    /** Returns the current schedule as pretty-printed JSON, or null when empty. */
    fun exportScheduleText(): String? {
        val schedule = _state.value.schedule
        if (schedule.isEmpty) {
            reportMessage(text(R.string.schedule_export_empty))
            return null
        }
        return ScheduleCodec.encode(schedule)
    }

    fun reportExportResult(success: Boolean) {
        reportMessage(text(if (success) R.string.schedule_export_success else R.string.schedule_export_error))
    }

    fun importScheduleText(text: String) {
        val imported = try {
            ScheduleCodec.decode(text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            reportMessage(text(R.string.schedule_import_error))
            return
        }
        if (!imported.withinDeviceLimits()) {
            reportMessage(text(R.string.schedule_too_large))
            return
        }
        updateSchedule(
            imported,
            undoable = true,
            message = text(R.string.schedule_import_success, imported.totalModules),
        )
    }

    // ------------------------------ Templates ----------------------------

    /** Adds a new template or replaces the user's template with the same id. */
    fun saveTemplate(template: LessonTemplate) {
        if (template.builtIn) return
        val current = customTemplates.value
        val updated = if (current.any { it.id == template.id }) {
            current.map { if (it.id == template.id) template else it }
        } else {
            current + template
        }
        customTemplates.value = updated
        templateStore.save(updated)
        reportMessage(text(R.string.template_saved, template.name))
    }

    fun deleteTemplate(id: String) {
        val updated = customTemplates.value.filterNot { it.id == id }
        customTemplates.value = updated
        templateStore.save(updated)
        reportMessage(text(R.string.template_deleted))
    }

    // ------------------------------ Settings -----------------------------

    fun saveSettings(baseUrl: String, token: String, ringDefault: Int, autoSyncTime: Boolean) {
        val normalizedUrl = normalizeBaseUrl(baseUrl)
        if (normalizedUrl == null) {
            reportMessage(text(R.string.settings_invalid_url))
            return
        }
        if (ringDefault !in MIN_RING_SECONDS..MAX_RING_SECONDS) {
            reportMessage(text(R.string.settings_invalid_duration))
            return
        }

        val old = _state.value.settings
        val updated = AppSettings(
            baseUrl = normalizedUrl,
            token = token.trim(),
            ringDefault = ringDefault,
            autoSyncTime = autoSyncTime,
        )
        store.save(updated)
        api.updateSettings(updated)
        savingSettings = true

        val urlChanged = old.baseUrl != updated.baseUrl
        if (urlChanged) {
            undoSnapshot = null
            scheduleLoadAttempted = false
            _state.update {
                UiState(settings = updated, connection = ConnectionState.Connecting)
            }
        } else {
            _state.update { it.copy(settings = updated) }
        }

        viewModelScope.launch {
            val deviceSaved = try {
                api.saveSettings(ringDefault).ok
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            } finally {
                // When the device did not take the value, the next poll
                // brings the app back in line with what actually rings.
                savingSettings = false
            }
            refreshStatus()
            reportMessage(text(if (deviceSaved) R.string.settings_saved else R.string.settings_saved_local))
        }
    }

    /**
     * Wipes the schedule and the settings on the device. The app adopts the
     * device defaults and drops any unsent edits.
     */
    fun factoryReset(network: Boolean) = action {
        val response = api.factoryReset(network)
        if (!response.ok) throw ApiException.Http(500, response.error ?: "storage_error")
        val updated = _state.value.settings.copy(ringDefault = DEFAULT_RING_SECONDS)
        store.save(updated)
        api.updateSettings(updated)
        undoSnapshot = null
        _state.update {
            it.copy(
                settings = updated,
                schedule = BellSchedule(),
                scheduleDirty = false,
                scheduleLoaded = true,
                undoAvailable = false,
                scheduleIssue = null,
                network = null,
                message = text(if (response.restart == true) R.string.reset_done_network else R.string.reset_done),
            )
        }
        // After a network reset the access point restarts; the next poll
        // reconnects once the phone has joined it again.
        if (response.restart != true) applyStatus(api.getStatus().toDomain())
    }

    // ------------------------------- Network -----------------------------

    fun loadNetwork() {
        viewModelScope.launch {
            try {
                val network = api.getNetwork()
                _state.update { it.copy(network = network) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Older firmware has no /network; the card stays hidden.
            }
        }
    }

    /**
     * Changes the access point and the token on the device. A null [password]
     * or [token] keeps the current value; an empty one makes the network open
     * or turns the token off. When the token changes, the app switches to it
     * right away so it keeps access.
     */
    fun saveNetwork(ssid: String, password: String?, token: String?) = action {
        val response = api.saveNetwork(NetworkUpdateDto(ssid = ssid.trim(), password = password, token = token))
        if (!response.ok) throw ApiException.Http(400, response.error ?: "invalid_request")
        if (token != null) {
            val updated = _state.value.settings.copy(token = token)
            store.save(updated)
            api.updateSettings(updated)
            _state.update { it.copy(settings = updated) }
        }
        val restart = response.restart == true
        _state.update {
            it.copy(
                network = it.network?.copy(
                    ssid = response.ssid ?: ssid,
                    open = if (password != null) password.isEmpty() else it.network.open,
                    tokenSet = if (token != null) token.isNotEmpty() else it.network.tokenSet,
                ),
                message = if (restart) {
                    text(R.string.network_saved_restart, response.ssid ?: ssid)
                } else {
                    text(R.string.network_saved)
                },
            )
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun reportMessage(message: String) {
        _state.update { it.copy(message = message) }
    }

    fun consumeScheduleIssue() {
        _state.update { it.copy(scheduleIssue = null) }
    }

    // ------------------------------- Errors ------------------------------

    private fun describeIssue(issue: ScheduleIssue): String = when (issue) {
        is ScheduleIssue.AutoWindowInvalid -> text(R.string.issue_auto_window)
        is ScheduleIssue.PackWindowInvalid -> text(R.string.issue_pack_window)
        is ScheduleIssue.TooManyPacks -> text(R.string.error_too_many_packs)
        is ScheduleIssue.TooManyPackModules -> text(R.string.error_too_many_pack_modules)
        is ScheduleIssue.TooManyPackDates -> text(R.string.error_too_many_pack_dates)
        is ScheduleIssue.InvalidModule -> text(R.string.issue_invalid_module)
        is ScheduleIssue.FirmwareTooOld -> text(R.string.issue_firmware_too_old)
    }

    private fun handleActionError(e: Throwable) {
        val offline = e is ApiException.NoWifi || e is ApiException.Timeout || e is ApiException.Network
        val message = mapError(e)
        _state.update {
            it.copy(
                busy = false,
                connection = if (offline) ConnectionState.Offline else it.connection,
                lastError = if (offline) message else it.lastError,
                message = message,
            )
        }
    }

    private fun mapError(e: Throwable): String = when (e) {
        is ApiException.NoWifi -> text(R.string.error_no_wifi)
        is ApiException.Timeout -> text(R.string.error_timeout)
        is ApiException.Network -> text(R.string.error_network, e.cause?.message ?: e.message.orEmpty())
        is ApiException.InvalidJson -> text(R.string.error_invalid_json)
        is ApiException.Http -> when (e.errorCode) {
            "unauthorized" -> text(R.string.error_unauthorized)
            "invalid_sec" -> text(R.string.error_invalid_sec)
            "invalid_mode" -> text(R.string.error_invalid_mode)
            "invalid_datetime" -> text(R.string.error_invalid_datetime)
            "invalid_schedule" -> text(R.string.error_invalid_schedule)
            "too_many_modules", "too_many_pack_modules" -> text(R.string.error_too_many_pack_modules)
            "too_many_packs" -> text(R.string.error_too_many_packs)
            "too_many_pack_dates" -> text(R.string.error_too_many_pack_dates)
            "invalid_ring_default" -> text(R.string.settings_invalid_duration)
            "invalid_json" -> text(R.string.error_invalid_json)
            "storage_error" -> text(R.string.error_storage_device)
            "rtc_not_found" -> text(R.string.device_error_rtc_not_found)
            "invalid_date" -> text(R.string.error_invalid_date)
            "invalid_ssid" -> text(R.string.network_invalid_ssid)
            "invalid_password" -> text(R.string.network_invalid_password)
            "invalid_token" -> text(R.string.network_invalid_token)
            else -> if (e.code >= 500) {
                text(R.string.error_http, e.code)
            } else {
                text(R.string.error_device_generic, e.errorCode)
            }
        }
        else -> text(R.string.error_unexpected, e.message ?: e.javaClass.simpleName)
    }

    private fun formatDrift(seconds: Long): String {
        val total = abs(seconds)
        val minutes = total / 60
        val rest = total % 60
        return when {
            minutes >= 60 -> text(R.string.drift_hours, minutes / 60, minutes % 60)
            minutes > 0 -> text(R.string.drift_minutes, minutes, rest)
            else -> text(R.string.drift_seconds, rest)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val AUTO_SYNC_INTERVAL_MS = 10 * 60_000L
        const val AUTO_SYNC_DRIFT_SEC = 60L
        val API_DATE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
