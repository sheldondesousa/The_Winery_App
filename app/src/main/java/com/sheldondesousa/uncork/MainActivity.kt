package com.sheldondesousa.uncork

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sheldondesousa.uncork.ui.conversation.ConversationRoute
import com.sheldondesousa.uncork.ui.splash.DemoModelLoader
import com.sheldondesousa.uncork.ui.splash.SplashRoute
import com.sheldondesousa.uncork.ui.theme.UncorkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UncorkTheme {
                var modelReady by remember { mutableStateOf(false) }

                if (modelReady) {
                    ConversationRoute()
                } else {
                    SplashRoute(
                        modelLoader = DemoModelLoader(),
                        onModelReady = { modelReady = true },
                    )
                }
            }
        }
    }
}
