package com.noti.logger.upload

import com.noti.logger.data.NotificationEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class UploadBatch(
    val batch: List<UploadItem>
)

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

/** Failure messages that are NOT "already exists" — genuine errors to retry and surface. */
fun List<String>.genuineFailures(): List<String> =
    filter { !it.contains("already exists", ignoreCase = true) }

@Serializable
data class UploadItem(
    @SerialName("device_id") val deviceId: String,
    val uid: String,
    @SerialName("package") val pkg: String,
    @SerialName("app_label") val appLabel: String?,
    /** ISO-8601 UTC timestamp (e.g. "2026-07-03T08:39:00.174Z") so datetime columns parse it directly. */
    @SerialName("post_time") val postTime: String,
    val title: String?,
    val text: String?,
    @SerialName("big_text") val bigText: String?,
    @SerialName("sub_text") val subText: String?,
    val category: String?
)

/** Epoch millis -> ISO-8601 UTC string. */
fun epochMillisToIso(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

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
