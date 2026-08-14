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

fun NotificationEntity.toUploadItem(deviceId: String): UploadItem = UploadItem(
    deviceId = deviceId,
    uid = uid,
    pkg = packageName,
    appLabel = appLabel,
    postTime = epochMillisToIso(postTime),
    title = title,
    text = text,
    bigText = bigText,
    subText = subText,
    category = category
)
