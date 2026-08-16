package dev.vynkor.agent.caps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dev.vynkor.agent.Contact
import dev.vynkor.agent.ContactsProvider

/** Contact lookup via ContactsContract, filtered by query. */
class ContactsProviderImpl(context: Context) : ContactsProvider {
    private val ctx = context.applicationContext
    private val granted = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    override fun list(query: String): List<Contact> {
        if (!granted) return emptyList()
        val result = mutableListOf<Contact>()
        val selection = if (query.isNotBlank()) {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        } else {
            null
        }
        val args = if (query.isNotBlank()) arrayOf("%$query%") else null
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            selection,
            args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        ) ?: return result
        cursor.use {
            val nameIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val seen = HashSet<Pair<String, String>>()
            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: ""
                val number = it.getString(numIdx) ?: ""
                if (seen.add(name to number)) {
                    result.add(Contact(name = name, phones = listOf(number), emails = emptyList()))
                }
            }
        }
        return result
    }
}
