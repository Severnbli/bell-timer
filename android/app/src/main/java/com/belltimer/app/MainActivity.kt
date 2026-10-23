package com.belltimer.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.belltimer.app.ui.BellTimerApp
import com.belltimer.app.ui.BellTimerTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BellTimerTheme {
                val appViewModel: AppViewModel = viewModel()
                LifecycleStartEffect(Unit) {
                    appViewModel.startPolling()
                    onStopOrDispose { appViewModel.stopPolling() }
                }
                BellTimerApp(appViewModel)
            }
        }
    }
}
