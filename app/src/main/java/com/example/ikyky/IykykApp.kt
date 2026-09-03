package com.example.ikyky

import android.app.Application
import com.example.ikyky.core.di.AppContainer

class IykykApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
