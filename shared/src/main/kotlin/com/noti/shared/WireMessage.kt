package com.noti.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject

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
        /** Why, for a [FAILED] the companion refused (stale, clock skew); empty otherwise and then
         *  omitted on the wire. Older Mains ignore it. */
        val reason: String = "",
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

    /**
     * Main → Companion: "re-send the SMS received since [sinceMs]" (the companion's clock, as it
     * stamped the relays; 0 = its default look-back). Sent when FCM reports it dropped messages
     * queued for Main (over 100 pending during an outage): the companion had already counted those
     * relays as sent. Main's content dedup drops whatever it already holds.
     */
    @Serializable
    @SerialName("catchup")
    data class CatchUp(
        val sinceMs: Long = 0,
    ) : WireMessage

    /** Companion → Main: the companion's current push endpoint, so Main can reach it with no copy-back. */
    @Serializable
    @SerialName("token")
    data class Token(
        val endpoint: String = "",
    ) : WireMessage

    /**
     * Main → Companion: "stream me your device status". [forSeconds] > 0 opens (or renews) a
     * subscription window; the companion sends a [StatusReport] every `pollSeconds` until it
     * expires. [forSeconds] == 0 cancels immediately - Main sends that when its Status tab goes
     * away, so the stream stops at once instead of running out the window.
     *
     * Relative rather than an absolute deadline because the two phones' clocks are independent.
     * The window is what makes this safe: Main renews while it is on screen, and a Main that is
     * force-stopped, killed, or offline simply stops renewing, so the companion falls silent on its
     * own rather than streaming forever to a device that is no longer listening.
     */
    @Serializable
    @SerialName("statusreq")
    data class StatusRequest(
        val forSeconds: Int = 0,
    ) : WireMessage

    /**
     * Companion → Main: one device-status snapshot. Sent on the polling cadence while a
     * [StatusRequest] window is open, and *also* out of band when an alert threshold trips - see
     * [trigger] - which is why the alert carries a full snapshot rather than a bare "battery low":
     * the user opening the notification wants to see the whole picture as it was at that moment.
     *
     * Every field has an "unknown" default so a companion that can't read one (permission not
     * granted, OEM quirk, older API level) simply omits it - `encodeDefaults = false` keeps those
     * off the wire, and Main renders them as "—" rather than as a false zero.
     */
    @Serializable
    @SerialName("status")
    data class StatusReport(
        /** Companion's clock when the snapshot was taken. Display only - never compare it against
         *  Main's clock to judge freshness (see [DeviceStatusPolicy.reportStale]). */
        val atMs: Long = 0,
        val trigger: Int = POLL,
        /** 0-100, or -1 when unknown. */
        val batteryPct: Int = -1,
        val charging: Boolean = false,
        val powerSave: Boolean = false,
        /** Cellular signal bars, 0-4, or -1 when unknown (READ_PHONE_STATE not granted). */
        val signalLevel: Int = -1,
        /** Cellular signal in dBm, or 0 when unknown. */
        val signalDbm: Int = 0,
        /** Radio technology as the platform names it: "LTE", "NR", "HSPA"… Blank when unknown. */
        val networkType: String = "",
        val roaming: Boolean = false,
        /** Wifi is the active transport. */
        val wifi: Boolean = false,
        /** Wifi bars, 0-4, or -1 when unknown. */
        val wifiLevel: Int = -1,
        /** Cellular is the active transport. */
        val mobileData: Boolean = false,
        /** The active network actually validated - i.e. real internet, not just an association. */
        val online: Boolean = false,
        val vpn: Boolean = false,
        val airplane: Boolean = false,
        /** Whole minutes wifi has been down; 0 while wifi is up. */
        val wifiDownMinutes: Int = 0,
    ) : WireMessage {
        companion object {
            /** A scheduled snapshot inside a subscription window. */
            const val POLL = 0
            const val BATTERY_LOW = 1
            const val BATTERY_OK = 2
            const val WIFI_LOST = 3
            const val WIFI_BACK = 4

            /** Triggers Main should raise a notification for, rather than only repaint the card. */
            fun isAlert(trigger: Int): Boolean = trigger != POLL
        }
    }

    /**
     * Main → Companion: the alerting thresholds. Main owns them (they are configured there) but the
     * companion is what evaluates them, since battery and wifi alerts have to fire while Main's app
     * is asleep. [configVersion] (epoch millis when Main built the push) is the same replay/rollback
     * guard [WebhookConfig] uses: the companion only accepts a version higher than the last applied,
     * so a captured older push can't quietly restore a threshold the user has since raised.
     */
    @Serializable
    @SerialName("statuscfg")
    data class StatusConfig(
        val alertsEnabled: Boolean = true,
        val batteryPct: Int = DeviceStatusPolicy.DEFAULT_BATTERY_PCT,
        val wifiLossMinutes: Int = DeviceStatusPolicy.DEFAULT_WIFI_LOSS_MINUTES,
        val pollSeconds: Int = DeviceStatusPolicy.DEFAULT_POLL_SECONDS,
        val configVersion: Long = 0,
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
        /** The sender has its heartbeat switched off: it answers force-checks with this (and sends
         *  one when switched off) so the peer stops expecting beats instead of warning "not
         *  responding" about a phone that is fine. */
        val off: Boolean = false,
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

    /** Plain form, for a copy kept on this phone (never sent). Pushes use [encodeFor]. */
    fun encode(message: WireMessage): String = json.encodeToString(message)

    /** @throws Exception on malformed input or an unknown type. Pushes use [decodeFrom]. */
    fun decode(text: String): WireMessage = json.decodeFromString(text)

    /**
     * Which way a push travels. Both directions share one key, so without this a message could be
     * captured and sent back to the phone that wrote it, and still decrypt: a Main token announce
     * reflected to Main would point its sends at itself; a reflected heartbeat would fake the peer
     * being alive. The tag sits inside the encrypted, authenticated payload, so it can't be changed
     * without the key.
     */
    enum class Direction(val tag: String) { MAIN_TO_COMPANION("m2c"), COMPANION_TO_MAIN("c2m") }

    private const val DIR_FIELD = "d"

    /** [message] tagged as travelling [direction]: `{"t":..., "d":"m2c", ...}`. */
    fun encodeFor(message: WireMessage, direction: Direction): String {
        val obj = json.encodeToJsonElement(WireMessage.serializer(), message).jsonObject
        return JsonObject(obj + (DIR_FIELD to JsonPrimitive(direction.tag))).toString()
    }

    /** A push that didn't carry the tag at all: from a peer on a version before tags. */
    class UntaggedException : IllegalArgumentException("untagged push - the other phone needs updating")

    /** A push tagged for the other direction: a reflected message. */
    class WrongDirectionException : IllegalArgumentException("push tagged for the other direction")

    /**
     * Decodes a push that must be travelling [expected]. Untagged pushes are refused (a hard cut:
     * both phones must run a version with tags), as is one tagged the other way.
     * @throws UntaggedException, WrongDirectionException, or on malformed input.
     */
    fun decodeFrom(text: String, expected: Direction): WireMessage {
        val obj = json.parseToJsonElement(text).jsonObject
        val tag = (obj[DIR_FIELD] as? JsonPrimitive)?.contentOrNull ?: throw UntaggedException()
        if (tag != expected.tag) throw WrongDirectionException()
        return json.decodeFromJsonElement(WireMessage.serializer(), JsonObject(obj - DIR_FIELD))
    }

    /** True when [text] decodes as a wire message but carries no direction tag. */
    fun isUntagged(text: String): Boolean = try {
        val obj = json.parseToJsonElement(text).jsonObject
        obj[DIR_FIELD] == null && obj["t"] != null
    } catch (e: Exception) {
        false
    }
}
