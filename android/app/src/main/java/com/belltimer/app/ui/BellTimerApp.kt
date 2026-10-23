package com.belltimer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.belltimer.app.AppViewModel
import com.belltimer.app.R

private enum class Tab(val title: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    Now(R.string.tab_now, Icons.Outlined.NotificationsActive, Icons.Filled.NotificationsActive),
    Schedule(R.string.tab_schedule, Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    Settings(R.string.tab_settings, Icons.Outlined.Tune, Icons.Filled.Tune),
}

@Composable
fun BellTimerApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Now) }
    var editingPackId by rememberSaveable { mutableIntStateOf(NO_PACK) }
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.undo)
    val openLabel = stringResource(R.string.open)

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        viewModel.clearMessage()
        val actionLabel = when {
            state.undoAvailable -> undoLabel
            state.scheduleIssue != null && tab != Tab.Schedule && editingPackId == NO_PACK -> openLabel
            else -> null
        }
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            withDismissAction = actionLabel == null,
            duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            if (actionLabel == undoLabel) {
                viewModel.undoLastScheduleChange()
            } else {
                tab = Tab.Schedule
                viewModel.consumeScheduleIssue()
            }
        }
    }

    // A pack that disappeared (undo, reload) closes its editor.
    val editing = editingPackId != NO_PACK && state.schedule.packById(editingPackId) != null

    AnimatedContent(
        targetState = editing,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut()
            } else {
                fadeIn() togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
            }
        },
        label = "editor",
    ) { showEditor ->
        if (showEditor) {
            PackEditorScreen(
                state = state,
                viewModel = viewModel,
                packId = editingPackId,
                snackbarHostState = snackbarHostState,
                onBack = { editingPackId = NO_PACK },
            )
        } else {
            // Back from Schedule or Settings returns to the main tab; only
            // the main tab lets the system close the app.
            BackHandler(enabled = tab != Tab.Now) { tab = Tab.Now }
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        for (item in Tab.entries) {
                            val selected = item == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { tab = item },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (item == Tab.Schedule && state.scheduleDirty) Badge()
                                        },
                                    ) {
                                        Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null)
                                    }
                                },
                                label = { Text(stringResource(item.title)) },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when (tab) {
                        Tab.Now -> NowScreen(
                            state = state,
                            viewModel = viewModel,
                            onOpenSchedule = { tab = Tab.Schedule },
                        )
                        Tab.Schedule -> ScheduleScreen(
                            state = state,
                            viewModel = viewModel,
                            onOpenPack = { editingPackId = it },
                        )
                        Tab.Settings -> SettingsScreen(state = state, viewModel = viewModel)
                    }
                }
            }
        }
    }
}

private const val NO_PACK = -1
