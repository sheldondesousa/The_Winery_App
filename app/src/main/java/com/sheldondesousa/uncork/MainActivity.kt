package com.sheldondesousa.uncork

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sheldondesousa.uncork.model.GemmaConversationResponder
import com.sheldondesousa.uncork.model.ModelFileManager
import com.sheldondesousa.uncork.ui.conversation.ConversationRoute
import com.sheldondesousa.uncork.ui.splash.SplashRoute
import com.sheldondesousa.uncork.ui.theme.UncorkTheme

class MainActivity : ComponentActivity() {
    private lateinit var gemmaResponder: GemmaConversationResponder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val modelFileManager = ModelFileManager(applicationContext)
        gemmaResponder = GemmaConversationResponder(applicationContext, modelFileManager.modelFile)
        setContent {
            UncorkTheme {
                var modelReady by remember { mutableStateOf(false) }

                if (modelReady) {
                    ConversationRoute(responder = gemmaResponder)
                } else {
                    SplashRoute(
                        modelFileManager = modelFileManager,
                        onModelReady = { modelReady = true },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        gemmaResponder.close()
        super.onDestroy()
    }
}
