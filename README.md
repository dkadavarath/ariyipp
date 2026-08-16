# ariyipp

![Platform](https://img.shields.io/badge/platform-Android-3ddc84)
![minSdk](https://img.shields.io/badge/minSdk-26-1565c0)
![targetSdk](https://img.shields.io/badge/targetSdk-35-1565c0)
![Release](https://img.shields.io/badge/release-v1.0-1565c0)
![Language](https://img.shields.io/badge/kotlin-100%25-7f52ff)

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
- **Duplicate-proof** — the relay works off the SMS provider's stable row ids, so a phone that fires
  the incoming-SMS broadcast twice can't double-send.
- **Liveness heartbeat** — each device periodically checks the other is reachable and warns (with a
  Retry action) if it goes offline. Toggle it off if you don't want it.
- **Per-conversation mute**, unread badges, one-time-code copy, and inline reply from the notification.
- **Encrypted backup & restore** through the system file picker, passphrase-protected.
- **Optional extras:** capture this phone's notifications and forward them to a webhook (e.g. an
  [n8n](https://n8n.io) node), and push the webhook config to the companion.
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

## Bring your own Firebase (BYO-FCM)

The app ships **without** any Firebase credentials. You supply your own so the relay runs entirely on
your own project:

1. Create a Firebase project and add an Android app with the id `com.noti.logger`.
2. Enable **Cloud Messaging** and download `google-services.json` into `app/`.
3. Generate a **service-account key** (JSON) with the *Firebase Cloud Messaging API* enabled, and
   import it on-device (Pairing → Import service-account key) on both phones.

`google-services.json`, service-account keys, and signing keys are all gitignored — they never land
in the repo.

## Configure on device

1. Install on both phones; pick **Main** on one and **Companion** on the other.
2. On the companion, grant SMS access and exempt it from battery optimization (Status screen).
3. Import the service-account key on both, then pair: scan the main device's QR from the companion.
4. On the main device, turn on **Receive relayed messages**.

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
