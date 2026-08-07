package com.noti.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * The webhook wire format, shared by noti and the sender app so both POST the identical JSON shape:
 * `{ "batch": [ { device_id, uid, package, app_label, post_time, title, text, big_text, sub_text,
 * category } ] }`.
 */
@Serializable
data class UploadBatch(val batch: List<UploadItem>)

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
    val category: String?,
)

/** Epoch millis -> ISO-8601 UTC string. */
fun epochMillisToIso(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()
