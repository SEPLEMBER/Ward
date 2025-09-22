package com.nemesis.droidcrypt

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Устанавливаем текст хардкодом ещё и из кода
        val textView: TextView = findViewById(R.id.textView)
        textView.text = "Hello World"
    }
}
