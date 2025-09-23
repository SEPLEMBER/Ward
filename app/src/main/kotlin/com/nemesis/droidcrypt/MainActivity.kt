package org.syndes.terminal

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var terminalOutput: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button

    private val terminal = Terminal()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        terminalOutput = findViewById(R.id.terminalOutput)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)

        // Вступительное сообщение
        terminalOutput.text = "Welcome to Syndes Terminal!\nType 'help' to see commands.\n\n"

        sendButton.setOnClickListener {
            val command = inputField.text.toString()
            if (command.isNotBlank()) {
                val result = terminal.execute(command)
                terminalOutput.append("\n> $command\n$result\n")
                inputField.text.clear()
            }
        }
    }
}
