package com.noti.logger.upload

import com.noti.logger.data.NotificationEntity
import com.noti.shared.UploadItem
import com.noti.shared.epochMillisToIso
import kotlinx.serialization.Serializable

/**
 * Webhook response entry. The endpoint always replies HTTP 200 with a JSON array of these,
 * e.g. `[{"success":["uid1"],"failure":["Key (uid)=(uid2) already exists."]}]`.
 * `success` holds plain uids that were stored; `failure` holds error messages (uid embedded).
 */
@Serializable
data class UploadResponseEntry(
    val success: List<String> = emptyList(),
    val failure: List<String> = emptyList()
)

/** Matches the uid embedded in an endpoint error message, e.g. `Key (uid)=(abc) already exists.` */
private val UID_IN_MESSAGE = Regex("""\(uid\)=\(([^)]*)\)""")

/**
 * Uids from "already exists" failures. A duplicate uid means the record is already stored
 * downstream, so it is safe to delete locally (idempotent success) rather than retry forever.
 */
fun List<String>.alreadyExistsUids(): Set<String> =
    asSequence()
        .filter { it.contains("already exists", ignoreCase = true) }
        .mapNotNull { UID_IN_MESSAGE.find(it)?.groupValues?.getOrNull(1) }
        .filter { it.isNotBlank() }
        .toSet()

/** Failure messages that are NOT "already exists" - genuine errors to retry and surface. */
fun List<String>.genuineFailures(): List<String> =
    filter { !it.contains("already exists", ignoreCase = true) }

/**
 * The webhook's copy of a captured notification. With [mask] on, one-time codes and card numbers
 * are masked here, in what leaves the phone - not in the stored row, which reminder rules read and
 * which a parking zone "code is 4521" or a delivery PIN must reach intact.
 */
fun NotificationEntity.toUploadItem(deviceId: String, mask: Boolean = false): UploadItem {
    val m: (String?) -> String? = if (mask) com.noti.logger.redact.SensitiveMask::apply else { t -> t }
    return UploadItem(
        deviceId = deviceId,
        uid = uid,
        pkg = packageName,
        appLabel = appLabel,
        postTime = epochMillisToIso(postTime),
        title = m(title),
        text = m(text),
        bigText = m(bigText),
        subText = m(subText),
        category = category
    )
}
