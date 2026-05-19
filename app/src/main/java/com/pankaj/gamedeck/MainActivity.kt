package com.pankaj.gamedeck

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.color.DynamicColors

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply Material You dynamic colors if available
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        findViewById<android.view.View>(R.id.btn_add_widget).setOnClickListener {
            Toast.makeText(this, R.string.instructions_text, Toast.LENGTH_LONG).show()
        }
    }
}