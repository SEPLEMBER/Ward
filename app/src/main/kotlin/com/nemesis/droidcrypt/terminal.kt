package org.syndes.terminal

class Terminal {

    private val history = mutableListOf<String>()

    fun execute(command: String): String {
        history.add(command)

        return when (command.trim().lowercase()) {
            "help" -> "Available commands: help, clear, history, echo <text>"
            "clear" -> {
                history.clear()
                "Screen cleared."
            }
            "history" -> history.joinToString("\n")
            else -> {
                if (command.startsWith("echo ")) {
                    command.removePrefix("echo ")
                } else {
                    "Unknown command: $command"
                }
            }
        }
    }
}
