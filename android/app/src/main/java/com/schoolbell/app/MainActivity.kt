package com.schoolbell.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.schoolbell.app.ui.SchoolBellApp
import com.schoolbell.app.ui.SchoolBellTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SchoolBellTheme {
                val appViewModel: AppViewModel = viewModel()
                LifecycleStartEffect(Unit) {
                    appViewModel.startPolling()
                    onStopOrDispose { appViewModel.stopPolling() }
                }
                SchoolBellApp(appViewModel)
            }
        }
    }
}
