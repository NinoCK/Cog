package com.bestassistant.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.PhoneNumberUtils
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import java.util.Locale

/** One call in the phone's history: [name] as the phone saved it, or null for a number not in the contacts. */
@Immutable
data class PhoneCall(val number: String, val name: String?, val at: Long, val missed: Boolean) {
    /** The name, or the number written out. */
    val who: String get() = name?.takeIf { it.isNotBlank() } ?: shownNumber

    val shownNumber: String
        get() = PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number
}

/** The calls the phone has made and received, read from its call log ("Call history" permission). */
class CallHistory(private val context: Context) {

    fun canRead(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /** The missed calls of the last [hours] hours, newest first. */
    fun missed(hours: Int = MISSED_HOURS, limit: Int = MAX_MISSED): List<PhoneCall> {
        val since = System.currentTimeMillis() - hours * 3_600_000L
        return query(
            "${CallLog.Calls.TYPE} IN (${CallLog.Calls.MISSED_TYPE}, ${CallLog.Calls.REJECTED_TYPE}) AND ${CallLog.Calls.DATE} > ?",
            arrayOf(since.toString()),
            limit,
        )
    }

    /** The last call someone made to this phone, answered or not. */
    fun lastIncoming(): PhoneCall? = query(
        "${CallLog.Calls.TYPE} IN (${CallLog.Calls.INCOMING_TYPE}, ${CallLog.Calls.MISSED_TYPE}, ${CallLog.Calls.REJECTED_TYPE})",
        null,
        1,
    ).firstOrNull()

    /** Who "call back" means: the last missed call, else the last call that came in. */
    fun toCallBack(): PhoneCall? = query(
        "${CallLog.Calls.TYPE} IN (${CallLog.Calls.MISSED_TYPE}, ${CallLog.Calls.REJECTED_TYPE})",
        null,
        1,
    ).firstOrNull() ?: lastIncoming()

    fun lastOutgoing(): PhoneCall? = query("${CallLog.Calls.TYPE} = ${CallLog.Calls.OUTGOING_TYPE}", null, 1).firstOrNull()

    private fun query(selection: String, args: Array<String>?, limit: Int): List<PhoneCall> {
        if (!canRead()) return emptyList()
        return runCatching {
            val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.TYPE)
            // Calls from hidden numbers have no number to call back.
            val withNumber = "($selection) AND ${CallLog.Calls.NUMBER} != ''"
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, withNumber, args, "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        val number = cursor.getString(0) ?: continue
                        val type = cursor.getInt(3)
                        add(
                            PhoneCall(
                                number = number,
                                name = cursor.getString(1),
                                at = cursor.getLong(2),
                                missed = type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE,
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val MISSED_HOURS = 24
        const val MAX_MISSED = 5
    }
}
