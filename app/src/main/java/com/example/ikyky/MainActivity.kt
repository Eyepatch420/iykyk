package com.example.ikyky

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.example.ikyky.core.di.AppViewModelFactory
import com.example.ikyky.core.di.LocalAppViewModelFactory
import com.example.ikyky.core.navigation.AppNavGraph
import com.example.ikyky.core.ui.theme.IkykyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as IykykApp).container
        val vmFactory = AppViewModelFactory(container)

        setContent {
            IkykyTheme {
                CompositionLocalProvider(LocalAppViewModelFactory provides vmFactory) {
                    // Each screen owns its own AppScreen/AppTopBar and handles
                    // system-bar insets itself, so no outer Scaffold here.
                    AppNavGraph()
                }
            }
        }
    }
}
