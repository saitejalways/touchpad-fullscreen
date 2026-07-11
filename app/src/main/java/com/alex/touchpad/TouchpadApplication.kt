package com.alex.touchpad

import android.app.Application
import com.alex.touchpad.core.AppContainer

class TouchpadApplication : Application() {
    val appContainer: AppContainer by lazy { AppContainer(this) }
}
