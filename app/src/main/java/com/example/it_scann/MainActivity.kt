package com.example.it_scann

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.it_scann.ui.theme.IT_scannTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Thread.sleep(166)
        installSplashScreen()
        setContentView(R.layout.home_activity)
        Log.d("opencvkc", "Message OpenCV is: ${OpenCVLoader.initDebug()}")

        findViewById<Button>(R.id.btn_scan)
            .setOnClickListener {
                setContentView(R.layout.activity_camera_scan)
                print("birth")
            }

    }

}