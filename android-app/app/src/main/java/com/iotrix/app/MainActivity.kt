package com.iotrix.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.iotrix.app.ui.C
import com.iotrix.app.ui.IoTrixAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IoTrixAppTheme {
                Surface(Modifier.fillMaxSize(), color = C.Bg) {
                    IoTrixApp()
                }
            }
        }
    }
}
