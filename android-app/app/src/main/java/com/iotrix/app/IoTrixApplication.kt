package com.iotrix.app

import android.app.Application
import com.iotrix.app.core.LocationHelper
import com.iotrix.app.core.RideMonitor
import com.iotrix.app.core.RideService
import com.iotrix.app.data.Prefs

class IoTrixApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        LocationHelper.init(this)
        RideMonitor.init(this)
        RideService.ensureChannels(this)
    }
}
