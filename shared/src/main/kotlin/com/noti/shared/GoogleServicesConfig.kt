package com.noti.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The subset of a `google-services.json` needed to init Firebase (FCM) at runtime instead of at
 *  build time — the app's BYO-FCM story for the receive side (the send side already imports a
 *  service-account key the same way). */
data class FirebaseAppConfig(
    val apiKey: String,
    val applicationId: String,
    val projectId: String,
    val gcmSenderId: String,
)

@Serializable
private data class GoogleServicesFile(
    @SerialName("project_info") val projectInfo: ProjectInfo,
    val client: List<Client> = emptyList(),
)

@Serializable
private data class ProjectInfo(
    @SerialName("project_id") val projectId: String,
    @SerialName("project_number") val projectNumber: String,
)

@Serializable
private data class Client(
    @SerialName("client_info") val clientInfo: ClientInfo,
    @SerialName("api_key") val apiKey: List<ApiKeyEntry> = emptyList(),
)

@Serializable
private data class ClientInfo(
    @SerialName("mobilesdk_app_id") val mobilesdkAppId: String,
    @SerialName("android_client_info") val androidClientInfo: AndroidClientInfo? = null,
)

@Serializable
private data class AndroidClientInfo(
    @SerialName("package_name") val packageName: String = "",
)

@Serializable
private data class ApiKeyEntry(
    @SerialName("current_key") val currentKey: String,
)

object GoogleServices {
    private val json = Json { ignoreUnknownKeys = true }

    /** Cheap structural check (no exception) — used to route an imported file before fully parsing it. */
    fun looksLikeGoogleServices(text: String): Boolean = try {
        val root: JsonObject = Json.parseToJsonElement(text).jsonObject
        root.containsKey("project_info") && root.containsKey("client")
    } catch (e: Exception) {
        false
    }

    /**
     * Parses a `google-services.json` and extracts the config for [packageName]'s client entry
     * (falling back to the first entry if the package isn't matched — a single-app project is the
     * common case). @throws Exception if the file isn't a google-services.json or has no client/key.
     */
    fun parse(text: String, packageName: String): FirebaseAppConfig {
        val file = json.decodeFromString(GoogleServicesFile.serializer(), text)
        val client = file.client.firstOrNull { it.clientInfo.androidClientInfo?.packageName == packageName }
            ?: file.client.firstOrNull()
            ?: throw IllegalArgumentException("No client entry in google-services.json")
        val apiKey = client.apiKey.firstOrNull()?.currentKey
            ?: throw IllegalArgumentException("No api_key in google-services.json")
        return FirebaseAppConfig(
            apiKey = apiKey,
            applicationId = client.clientInfo.mobilesdkAppId,
            projectId = file.projectInfo.projectId,
            gcmSenderId = file.projectInfo.projectNumber,
        )
    }
}
