package com.example.app

import android.app.Application
import com.google.android.filament.utils.Utils

class ExampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Utils.init()
    }

}
