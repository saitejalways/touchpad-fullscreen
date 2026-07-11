package com.alex.touchpad.ui

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.alex.touchpad.R
import io.noties.markwon.Markwon

class HelpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        val contentText = findViewById<TextView>(R.id.helpContentText)
        val markdown = resources.openRawResource(R.raw.help_readme)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val markwon = Markwon.create(this)
        markwon.setMarkdown(contentText, markdown)
    }
}
