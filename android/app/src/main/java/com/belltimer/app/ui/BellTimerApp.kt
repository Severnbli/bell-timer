package com.belltimer.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.belltimer.app.AppViewModel
import com.belltimer.app.ConnectionState
import com.belltimer.app.R
import com.belltimer.app.UiState

private sealed interface Screen {
    data object Main : Screen
    data object Schedule : Screen
    data object Settings : Screen
    data class Editor(val packId: Int) : Screen
}

@Composable
fun BellTimerApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var scheduleOpen by rememberSaveable { mutableStateOf(false) }
    var editorPackId by rememberSaveable { mutableIntStateOf(-1) }
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.undo)
    val openIssueLabel = stringResource(R.string.schedule_issue_open)

    LaunchedEffect(state.message, state.undoAvailable, state.scheduleIssue) {
        val message = state.message ?: return@LaunchedEffect
        val actionLabel = when {
            state.undoAvailable -> undoLabel
            state.scheduleIssue != null -> openIssueLabel
            else -> null
        }
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            if (state.undoAvailable) {
                viewModel.undoLastScheduleChange()
            } else {
                scheduleOpen = true
            }
        }
        viewModel.clearMessage()
    }

    val route = when {
        settingsOpen -> Screen.Settings
        editorPackId >= 0 -> Screen.Editor(editorPackId)
        scheduleOpen -> Screen.Schedule
        else -> Screen.Main
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                when {
                    targetState is Screen.Schedule && initialState is Screen.Main -> {
                        (slideInVertically { it } + fadeIn()) togetherWith
                            (slideOutVertically { it / 4 } + fadeOut())
                    }
                    initialState is Screen.Schedule && targetState is Screen.Main -> {
                        (slideInVertically { -it / 4 } + fadeIn()) togetherWith
                            (slideOutVertically { it } + fadeOut())
                    }
                    else -> fadeIn() togetherWith fadeOut()
                }
            },
            label = "bell-timer-root",
        ) { current ->
            when (current) {
                Screen.Main -> Box(modifier = Modifier.padding(innerPadding)) {
                    MainScreen(
                        state = state,
                        viewModel = viewModel,
                        onOpenSchedule = { scheduleOpen = true },
                        onOpenSettings = { settingsOpen = true },
                    )
                }
                Screen.Schedule -> Box(modifier = Modifier.padding(innerPadding)) {
                    ScheduleScreen(
                        state = state,
                        viewModel = viewModel,
                        onBack = { scheduleOpen = false },
                        onOpenPack = { id -> editorPackId = id },
                    )
                }
                Screen.Settings -> SettingsScreen(
                    state = state,
                    viewModel = viewModel,
                    onBack = { settingsOpen = false },
                )
                is Screen.Editor -> ScheduleEditorScreen(
                    state = state,
                    viewModel = viewModel,
                    packId = current.packId,
                    onBack = {
                        editorPackId = -1
                        scheduleOpen = true
                    },
                )
            }
        }
    }
}

@Composable
private fun MainScreen(
    state: UiState,
    viewModel: AppViewModel,
    onOpenSchedule: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        MainTopBar(
            state = state,
            onSettings = onOpenSettings,
        )
        Box(modifier = Modifier.weight(1f)) {
            ControlScreen(state, viewModel)
        }
        ScheduleHintBar(
            dirty = state.scheduleDirty,
            onOpen = onOpenSchedule,
        )
    }
}

@Composable
private fun MainTopBar(state: UiState, onSettings: () -> Unit) {
    val time = state.status?.time
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionIcon(state.connection)
        Text(
            text = time?.deviceClock() ?: "--:--:--",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontFeatureSettings = TabularNumbers.fontFeatureSettings,
            ),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSettings) {
            Icon(
                Icons.Default.Settings,
                contentDescription = stringResource(R.string.tab_settings),
            )
        }
    }
}

@Composable
private fun ConnectionIcon(connection: ConnectionState) {
    val color = when (connection) {
        ConnectionState.Online -> Color(0xFF2E7D32)
        ConnectionState.Connecting -> MaterialTheme.colorScheme.primary
        ConnectionState.Offline -> MaterialTheme.colorScheme.error
    }
    val description = when (connection) {
        ConnectionState.Online -> stringResource(R.string.connection_online)
        ConnectionState.Connecting -> stringResource(R.string.connection_connecting)
        ConnectionState.Offline -> stringResource(R.string.connection_offline)
    }
    Icon(
        imageVector = Icons.Default.Wifi,
        contentDescription = description,
        tint = color,
        modifier = Modifier.size(24.dp),
    )
}

@Composable
private fun ScheduleHintBar(dirty: Boolean, onOpen: () -> Unit) {
    var drag by remember { mutableFloatStateOf(0f) }
    val openThresholdPx = with(LocalDensity.current) { 56.dp.toPx() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        drag += amount
                        if (drag <= -openThresholdPx) {
                            drag = 0f
                            onOpen()
                        }
                    },
                    onDragEnd = { drag = 0f },
                    onDragCancel = { drag = 0f },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        RoundedCornerShape(2.dp),
                    ),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.schedule_open_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (dirty) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
