package com.nervesparks.iris.irisapp

import android.app.Application

class IrisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
