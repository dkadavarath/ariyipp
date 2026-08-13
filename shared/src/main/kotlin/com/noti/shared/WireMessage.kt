package com.noti.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Which role a device plays in the pair. Stored per-device; chosen at onboarding, switchable later. */
@Serializable
enum class Role { MAIN, COMPANION }

/**
 * The single encrypted wire type between the two paired devices, tagged so one app that can host
 * either role knows what it received. Serialized with a class discriminator field `t`, e.g.
 * `{"t":"relay",...}`. This is the merged app's wire format — it supersedes the untyped RelayMessage
 * / SendCommand once both sides run ariyipp. New fields on any variant default, so an older peer that
 * omits them still decodes (forward-compatible).
 */
@Serializable
sealed interface WireMessage {

    /** Companion → Main: a relayed SMS to show and notify. */
    @Serializable
    @SerialName("relay")
    data class Relay(
        val title: String = "",
        val body: String = "",
        val dedupe: String = "",
        val time: Long = 0,
    ) : WireMessage

    /** Main → Companion: send this SMS from the SIM. */
    @Serializable
    @SerialName("command")
    data class Command(
        val to: String = "",
        val body: String = "",
        val sim: Int = -1,
    ) : WireMessage

    /** Main → Companion: overwrite the companion's webhook config (companion may ignore via a toggle). */
    @Serializable
    @SerialName("config")
    data class WebhookConfig(
        val enabled: Boolean = false,
        val url: String = "",
        val authHeaderName: String = "",
        val authHeaderPrefix: String = "",
        val authToken: String = "",
    ) : WireMessage

    /** Companion → Main: the companion's current push endpoint, so Main can reach it with no copy-back. */
    @Serializable
    @SerialName("token")
    data class Token(
        val endpoint: String = "",
    ) : WireMessage

    /**
     * Either way: a liveness ping. Both devices send one periodically; each tracks the peer's last
     * arrival and warns when too many are missed. [request] = "pong back now" (the force-retry ping),
     * which the receiver answers with a non-request heartbeat.
     */
    @Serializable
    @SerialName("heartbeat")
    data class Heartbeat(
        val request: Boolean = false,
    ) : WireMessage
}

/** Serializes [WireMessage] to/from the string carried in the encrypted FCM payload. */
object Wire {
    private val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: WireMessage): String = json.encodeToString(message)

    /** @throws Exception on malformed input or an unknown type. */
    fun decode(text: String): WireMessage = json.decodeFromString(text)
}
