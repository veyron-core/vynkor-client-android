package dev.vynkor.agent.caps

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dev.vynkor.agent.ClipboardProvider

/** System clipboard access. */
class ClipboardProviderImpl(context: Context) : ClipboardProvider {
    private val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun read(): String? =
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    override fun write(text: String) {
        cm.setPrimaryClip(ClipData.newPlainText("vynkor", text))
    }
}
