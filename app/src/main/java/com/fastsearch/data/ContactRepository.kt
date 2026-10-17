package com.fastsearch.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.fastsearch.search.FuzzyMatcher
import com.fastsearch.search.SearchKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class Contact(
    val id: Long,
    val lookupKey: String,
    val name: String,
    val photoUri: Uri?,
    val number: String?,
    val starred: Boolean,
) {
    val lookupUri: Uri get() = Contacts.getLookupUri(id, lookupKey)
}

class ContactRepository(private val context: Context) {

    private val photos = LruCache<Uri, ImageBitmap>(64)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    suspend fun search(query: String, limit: Int = 4): List<Contact> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || !hasPermission()) return@withContext emptyList()
        runCatching {
            val digits = trimmed.count { it.isDigit() }
            val byNumber = digits >= 3 && trimmed.all { it.isDigit() || it in "+-() " }
            val contacts = if (byNumber) searchByNumber(trimmed, limit * 2) else searchByName(trimmed, limit * 2)
            val normalized = FuzzyMatcher.normalize(trimmed)
            contacts
                .sortedWith(
                    compareByDescending<Contact> { if (byNumber) 0 else FuzzyMatcher.score(normalized, SearchKey.of(it.name)) }
                        .thenByDescending { it.starred }
                        .thenBy { it.name.lowercase() },
                )
                .take(limit)
        }.getOrDefault(emptyList())
    }

    private fun searchByName(query: String, limit: Int): List<Contact> {
        val uri = Contacts.CONTENT_FILTER_URI.buildUpon()
            .appendPath(query)
            .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, limit.toString())
            .build()
        val projection = arrayOf(
            Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY,
            Contacts.PHOTO_THUMBNAIL_URI, Contacts.STARRED, Contacts.HAS_PHONE_NUMBER,
        )
        val rows = ArrayList<Contact>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(2) ?: continue
                rows += Contact(
                    id = c.getLong(0),
                    lookupKey = c.getString(1) ?: continue,
                    name = name,
                    photoUri = c.getString(3)?.let(Uri::parse),
                    number = null,
                    starred = c.getInt(4) == 1,
                )
            }
        }
        val numbers = primaryNumbers(rows.map { it.id })
        return rows.map { it.copy(number = numbers[it.id]) }
    }

    private fun searchByNumber(query: String, limit: Int): List<Contact> {
        val uri = Phone.CONTENT_FILTER_URI.buildUpon()
            .appendPath(query)
            .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, limit.toString())
            .build()
        val projection = arrayOf(
            Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.DISPLAY_NAME_PRIMARY,
            Phone.PHOTO_THUMBNAIL_URI, Phone.STARRED, Phone.NUMBER,
        )
        val rows = LinkedHashMap<Long, Contact>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id in rows) continue
                rows[id] = Contact(
                    id = id,
                    lookupKey = c.getString(1) ?: continue,
                    name = c.getString(2) ?: c.getString(5) ?: continue,
                    photoUri = c.getString(3)?.let(Uri::parse),
                    number = c.getString(5),
                    starred = c.getInt(4) == 1,
                )
            }
        }
        return rows.values.toList()
    }

    /** Preferred (super-primary, then primary, then first) phone number per contact. */
    private fun primaryNumbers(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val result = HashMap<Long, String>()
        val selection = "${Phone.CONTACT_ID} IN (${ids.joinToString(",")})"
        val order = "${Phone.IS_SUPER_PRIMARY} DESC, ${Phone.IS_PRIMARY} DESC"
        context.contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.NUMBER), selection, null, order,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val number = c.getString(1) ?: continue
                result.putIfAbsent(id, number)
            }
        }
        return result
    }

    fun cachedPhoto(uri: Uri): ImageBitmap? = photos.get(uri)

    suspend fun photo(uri: Uri): ImageBitmap? = photos.get(uri) ?: withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        }.getOrNull()?.also { photos.put(uri, it) }
    }
}
