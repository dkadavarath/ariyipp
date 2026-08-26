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

    /** Companion → Main: a relayed SMS to show and notify.
     *  [part]/[parts] carry body chunking for long SMS: when the encrypted payload would exceed
     *  FCM's 4096-byte data cap, the sender splits the body into [parts] slices and ships one
     *  Relay each ([part] = 0-based index). Single-message relays keep the defaults, which are
     *  omitted on the wire (encodeDefaults = false). */
    @Serializable
    @SerialName("relay")
    data class Relay(
        val title: String = "",
        val body: String = "",
        val dedupe: String = "",
        val time: Long = 0,
        val part: Int = 0,
        val parts: Int = 1,
    ) : WireMessage

    /** Main → Companion: send this SMS from the SIM. [msgId] is Main's local row id for this
     *  message (a Room autoincrement id - always positive and strictly increasing, never reused),
     *  echoed back in a [DeliveryAck] so Main can match the ack to the right chat bubble. It also
     *  doubles as a replay-protection sequence number: the companion only accepts a [msgId] higher
     *  than the last one it processed. [issuedAt] (epoch millis) bounds how long a captured
     *  ciphertext stays replayable even before that high-water mark alone would reject it. */
    @Serializable
    @SerialName("command")
    data class Command(
        val to: String = "",
        val body: String = "",
        val sim: Int = -1,
        val msgId: Long = 0,
        val issuedAt: Long = 0,
    ) : WireMessage

    /**
     * Companion → Main: what happened to the [Command] with this [msgId]. [status] is one of
     * [DeliveryAck.RECEIVED] (the command reached the companion), [DeliveryAck.SMS_SENT] (handed
     * off to the SIM successfully), [DeliveryAck.SMS_DELIVERED] (a carrier delivery report
     * confirmed it reached the recipient - not all carriers send these, so this stage may never
     * arrive even for a message that delivered fine), or [DeliveryAck.FAILED].
     */
    @Serializable
    @SerialName("ack")
    data class DeliveryAck(
        val msgId: Long = 0,
        val status: Int = 0,
    ) : WireMessage {
        companion object {
            const val RECEIVED = 1
            const val SMS_SENT = 2
            const val SMS_DELIVERED = 3
            const val FAILED = 4
        }
    }

    /** Main → Companion: overwrite the companion's webhook config (companion may ignore via a toggle).
     *  [configVersion] (epoch millis when Main built this push) is a replay/rollback guard: the
     *  companion only accepts a version higher than the last one it applied, so a captured older
     *  config push can't restore a stale webhook URL or auth token. */
    @Serializable
    @SerialName("config")
    data class WebhookConfig(
        val enabled: Boolean = false,
        val url: String = "",
        val authHeaderName: String = "",
        val authHeaderPrefix: String = "",
        val authToken: String = "",
        val configVersion: Long = 0,
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
        // Omit fields equal to their defaults: trims ~30-40% off typical relay payloads (FCM's
        // data-message budget is 4096 bytes and base64 already adds +33%). Decode fills the
        // defaults back in, so old peers that DO encode defaults still decode fine.
        encodeDefaults = false
    }

    fun encode(message: WireMessage): String = json.encodeToString(message)

    /** @throws Exception on malformed input or an unknown type. */
    fun decode(text: String): WireMessage = json.decodeFromString(text)
}
