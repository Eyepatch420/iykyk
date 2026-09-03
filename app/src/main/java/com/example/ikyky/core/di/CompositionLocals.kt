package com.example.ikyky.core.di

import androidx.compose.runtime.compositionLocalOf

/**
 * Exposes the [AppViewModelFactory] to the Compose tree so feature screens can
 * do `viewModel(factory = LocalAppViewModelFactory.current)` without a DI
 * framework.
 */
val LocalAppViewModelFactory = compositionLocalOf<AppViewModelFactory> {
    error("AppViewModelFactory not provided")
}
