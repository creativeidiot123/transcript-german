package com.creativeidiot.transcriptgerman

import android.app.Application

class TranscriptApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, filesDir)
    }
}
