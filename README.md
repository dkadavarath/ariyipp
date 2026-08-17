# ariyipp

![Platform](https://img.shields.io/badge/platform-Android-3ddc84)
![minSdk](https://img.shields.io/badge/minSdk-26-1565c0)
![targetSdk](https://img.shields.io/badge/targetSdk-35-1565c0)
![Release](https://img.shields.io/badge/release-v1.0-1565c0)
![Language](https://img.shields.io/badge/kotlin-100%25-7f52ff)
![License](https://img.shields.io/badge/license-GPL--3.0-1565c0)

A lightweight, native-Kotlin Android app that **relays SMS between two of your phones** over an
**end-to-end encrypted** channel. One phone holds the SIM; the other is the one you carry. Incoming
SMS on the SIM phone show up as a chat on your main phone, and you can reply — the SIM phone sends it.

It's **one app with two roles**. Install it on both phones and pick a role on first run (switchable
later): **Main** (the hub you read and reply on) or **Companion** (the SMS phone with the SIM).

## Screens

| Status | Chat | Settings |
| --- | --- | --- |
| <img src="docs/screenshots/status.png" width="240"> | <img src="docs/screenshots/chat.png" width="240"> | <img src="docs/screenshots/settings.png" width="240"> |

## Features

- **SMS relay, both directions** — incoming SMS on the companion appear as a searchable chat on the
  main device; compose on the main device and the companion sends it from its SIM.
- **End-to-end encrypted** — messages are AES-256-GCM encrypted with a pre-shared key; the transport
  (FCM) only ever carries ciphertext.
- **One-way pairing by QR** — the main device owns and shows the shared key; the companion scans it
  and announces itself back automatically. No copying tokens by hand.
- **Liveness heartbeat** — each device periodically checks the other is reachable and warns (with a
  Retry action) if it goes offline. Toggle it off if you don't want it.
- **Per-conversation mute**, unread badges, one-time-code copy, and inline reply from the notification.
- **Encrypted backup & restore** through the system file picker, passphrase-protected.
- **Optional extras:** capture this phone's notifications and forward them to a webhook, and push the
  webhook config to the companion.
- **Theming:** light/dark/system, Material You, and a true-black AMOLED mode.

## How it works

Both phones run the same app. The **main** device receives relayed messages and shows them as chat;
the **companion** device (with the SIM) observes incoming SMS, encrypts each one, and pushes it to
the main device. Everything travels as ciphertext over Firebase Cloud Messaging (data messages,
high priority so they arrive under Doze).

```
 Companion (SIM)                         Main (hub)
 ──────────────                          ──────────
 incoming SMS  ──encrypt──▶  FCM  ──▶  decrypt ▶ chat + notification
 send from SIM ◀──decrypt──  FCM  ◀──  encrypt ◀ compose / reply
```

Pairing is one-way: the main device generates the shared AES key and shows it (plus its push token)
as a QR; the companion scans it, then announces its own push token back over the encrypted channel,
so reverse-send works with nothing copied by hand.

## Why FCM

The transport is Firebase Cloud Messaging, chosen deliberately to **reuse an existing, free, reliable
push channel** rather than stand up and run new infrastructure. FCM delivers data messages even under
Doze / aggressive battery optimization, costs nothing, and needs no self-hosted push server. The
**send** side isn't even a proprietary SDK: it's a plain HTTP v1 REST call authenticated with a
service-account key.

The catch is the **receive** side, which relies on the proprietary `firebase-messaging` / Google Play
Services library. A [UnifiedPush](https://unifiedpush.org) version (a fully open-source, Firebase-free
build) is on the cards if there's enough demand for it.

## Bring your own Firebase (BYO-FCM)

The app ships **without** any Firebase credentials — nothing is baked in at build time. You create a
free Firebase project and import two files **on-device**, so the relay runs entirely on your project.
Do this once; **both phones use the same project** (and the same two files).

You'll import two files, which do different jobs:

| File | What it's for | Which side uses it |
| --- | --- | --- |
| `google-services.json` | identifies the project so the device can **receive** pushes (get an FCM token) | receive |
| service-account key (JSON) | authorizes this device to **send** pushes to the other one | send |

### 1. Create the project and get `google-services.json`

1. Open the [Firebase console](https://console.firebase.google.com) and **Add project** (the free
   *Spark* plan is enough). Give it any name; Google Analytics is optional.
2. On the project dashboard, click the **Android** icon (*Add app*) and register an app with package
   name **`com.noti.logger`** — it must match exactly. You can leave the SHA-1 blank and **skip** the
   "add the SDK / run the app" steps.
3. Download the generated **`google-services.json`** onto (or transfer it to) the phone.

### 2. Get the service-account key

1. In the Firebase console, open **⚙ (Project settings) → Service accounts**.
2. Click **Generate new private key → Generate key**. A JSON file downloads — this is the
   service-account key (it carries the permission to send FCM messages). Keep it private; treat it
   like a password.
3. Confirm the send API is on: **Project settings → Cloud Messaging** should show *Firebase Cloud
   Messaging API (V1)* **Enabled**. New projects have it on by default; if it's off, follow the
   *Manage API in Google Cloud console* link and enable it there.

### 3. Import both on each phone

Settings → **Pairing → Import Firebase files** → pick **both** `google-services.json` and the
service-account key (multi-select them, or import one at a time — each updates independently). The
Pairing screen then shows this device's push token and the pairing QR.

> Both files live only in the app's **encrypted on-device settings** — they're never written into the
> repo (and signing keys stay gitignored too). Losing/rotating them just means re-importing.

## Configure on device

1. Install on both phones; pick **Main** on one and **Companion** on the other.
2. On the companion, grant SMS access and exempt it from battery optimization (Status screen).
3. Import the Firebase files on both (see above), then pair: scan the main device's QR from the companion.
4. On the main device, turn on **Receive relayed messages**.

## Webhook payload

Both webhook legs — notification capture on the main device, and SMS forwarding on the companion —
post the same JSON shape: a batch of items.

```json
{
  "batch": [
    {
      "device_id": "3f7c9e2a-...",
      "uid": "3f7c9e2a-...|sms|1735900000000",
      "package": "sms",
      "app_label": "SMS",
      "post_time": "2026-07-03T08:39:00.174Z",
      "title": "+15551234567",
      "text": "From: +15551234567\nMessage: Hello\nSent: 1735899999000\nReceived: 1735900000000\nSim: SIM 1",
      "big_text": null,
      "sub_text": null,
      "category": "sms"
    }
  ]
}
```

- Sent as `POST` with `Content-Type: application/json`, optionally gzipped (`Content-Encoding: gzip`).
- An optional auth header (name and value both configurable — e.g. `Authorization: Bearer <token>`,
  or a custom header for other auth schemes) is added when both are set.
- Forwarded SMS always have `package`/`category` = `"sms"`; captured notifications carry the
  originating app's real package name and label instead.
- Expected response: HTTP 200 with a JSON array acknowledging each `uid`:

```json
[{ "success": ["uid1"], "failure": ["Key (uid)=(uid2) already exists."] }]
```

  `success` uids are cleared from the local outbox. A `failure` message containing "already exists"
  is also treated as delivered (idempotent retry); any other failure is retried.

## Build from source

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # signed release (needs keystore.properties)
```

Release signing reads a gitignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`).

## Testing

```bash
./gradlew :shared:test                 # pure-JVM unit tests (crypto, wire, policy)
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest :sender:connectedDebugAndroidTest
```

## Security

- Message bodies are AES-256-GCM encrypted end-to-end; FCM carries ciphertext only.
- Settings that hold secrets (the shared key, service-account JSON) use `EncryptedSharedPreferences`.
- The app is not the default SMS handler and requests only the permissions it needs.

## Tech stack

Kotlin, Material 3, Room, WorkManager, Firebase Cloud Messaging (HTTP v1), kotlinx.serialization.

Multi-module: `:app` (the merged application), `:sender` (the companion role, an Android library), and
`:shared` (pure-JVM crypto, the typed wire format, and the FCM sender).

## License

ariyipp is free software licensed under the **GNU General Public License v3.0** — see
[LICENSE](LICENSE) for the full text. In short: you're free to use, study, share, and modify it, but
any derivative you distribute must also be released under the GPL-3.0.
