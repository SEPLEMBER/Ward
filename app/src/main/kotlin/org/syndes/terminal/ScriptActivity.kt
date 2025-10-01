package org.syndes.terminal

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader

class ScriptActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // UI not required for script runner — this activity acts as an entry point if needed
        finish()
    }

    companion object {
        /**
         * Convenience wrapper: start a script from Uri (DocumentFile single-uri).
         * Returns textual status (OK/Error).
         */
        fun startScriptFromUri(ctx: Context, scriptUri: Uri): String {
            val doc = DocumentFile.fromSingleUri(ctx, scriptUri) ?: return "Error: cannot access script"
            val text = try {
                ctx.contentResolver.openInputStream(doc.uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream)).readText()
                } ?: return "Error: cannot read script"
            } catch (t: Throwable) {
                return "Error: failed to read script: ${t.message}"
            }
            return ScriptHandler.startScriptFromText(ctx, text)
        }

        fun stopScript(scriptId: String): String {
            return ScriptHandler.stopScript(scriptId)
        }
    }
}
