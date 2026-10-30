package com.github.lfdk.OAM

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelProvider
import com.github.lfdk.OAM.projection.WirelessProbeViewModel
import com.github.lfdk.OAM.connection.HeadUnitColors
import com.github.lfdk.OAM.connection.HeadUnitDashboard

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val projection = ViewModelProvider(this)[WirelessProbeViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = HeadUnitColors) {
                HeadUnitDashboard(projection)
            }
        }
    }
}
