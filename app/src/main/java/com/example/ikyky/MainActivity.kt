package com.example.ikyky

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.example.ikyky.core.di.AppViewModelFactory
import com.example.ikyky.core.di.LocalAppViewModelFactory
import com.example.ikyky.core.navigation.AppNavGraph
import com.example.ikyky.ui.theme.IkykyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as IykykApp).container
        val vmFactory = AppViewModelFactory(container)

        setContent {
            IkykyTheme {
                CompositionLocalProvider(LocalAppViewModelFactory provides vmFactory) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        AppNavGraph(modifier = Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }
}
