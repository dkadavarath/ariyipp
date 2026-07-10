# noti

A deliberately lightweight, native-Kotlin Android app that logs **all** notifications to a
local database and forwards them, in batches, to an external webhook (e.g. an n8n webhook
node). Optimized for minimal battery, CPU, and memory — and to keep working under Doze /
aggressive battery optimization.

## Architecture

```
[System] --onNotificationPosted--> NotiListenerService --redact--> Room DB (outbox)
                                                                        |
                                        WorkManager (deferrable job) ---+--> gzip POST
                                                                                   |
                                                              Authorization: Bearer <token>
                                                                                   v
                                                                        Your webhook (n8n)
```

- **Capture:** `NotificationListenerService` is bound and kept alive **by the system**. It is
  event-driven and survives Doze / app-standby **without** a foreground service or wakelock —
  the single biggest battery win. (Verified: notifications posted during deep Doze are still captured.)
- **Storage:** Room/SQLite acts as a durable **outbox** (`uploaded` flag), so nothing is lost offline.
- **Upload:** `WorkManager` runs a deferrable `CoroutineWorker` that coalesces uploads into Doze
  maintenance windows, respecting network/charging constraints. Payloads are gzipped to minimize
  radio-on time. No listening socket, no persistent notification, no foreground service.

### Why push to a webhook (not host an on-device API)
A phone behind carrier/NAT is not reliably internet-reachable, and holding an inbound socket open
fights Doze and drains battery. Outbound batched pushes are the battery-optimal topology.

## Modules (`app/src/main/kotlin/com/noti/logger/`)
- `capture/NotiListenerService.kt` — captures, redacts, inserts (off the callback thread).
- `data/` — `NotificationEntity`, `NotificationDao`, `NotiDatabase` (Room, WAL, indexed).
- `redact/RedactionRules.kt` — drop excluded packages/keywords; optional metadata-only mode.
- `upload/` — `PayloadModels` (snake_case JSON), `Uploader` (HttpURLConnection + gzip).
- `work/` — `UploadWorker`, `UploadScheduler` (periodic / threshold / manual triggers + constraints).
- `config/Settings.kt` — EncryptedSharedPreferences (webhook URL, bearer token, trigger config, etc.).
- `util/AppLabelCache.kt` — LruCache over PackageManager label lookups.
- `boot/BootReceiver.kt`, `NotiApp.kt` — re-arm periodic work on boot / launch.
- `ui/MainActivity.kt` — single scrollable screen: onboarding, settings, status.

## Webhook contract
```
POST <webhook_url>
<auth-header>: <auth-value>
Content-Type: application/json
Content-Encoding: gzip

{ "batch": [ { "device_id","uid","package","app_label","post_time","title","text",
               "big_text","sub_text","category" } ] }
```
The endpoint replies **HTTP 200** with per-uid results:
```
[ { "success": ["<uid>", ...], "failure": ["<error msg with uid>", ...] } ]
```
The app **deletes only uids listed in `success`**; every other uid in the batch stays pending and
is retried, with the user alerted about failures. `uid` is stable/unique → dedupe idempotently.
Non-2xx: 5xx/network → silent retry; 4xx → alert + retry (transport/auth issue).

**Configurable auth header.** Defaults to `Authorization: Bearer <token>`. For an n8n
**Header Auth** credential, set *Auth header name* = `key` and clear the *Value prefix* so the raw
token is sent as `key: <token>`. Configured in the app's Settings screen (or `Settings.kt`).

### Live end-to-end test against a real webhook
`LiveWebhookTest` runs the real `UploadWorker` against a live endpoint; it is skipped unless a URL
is supplied (so it never leaks secrets in the normal suite). The token is passed base64-encoded to
survive shell/instrumentation arg parsing:
```
KEYB64=$(printf %s '<token>' | base64 -w0)
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.noti.logger.LiveWebhookTest \
  -Pandroid.testInstrumentationRunnerArguments.webhookUrl='https://host/webhook/<id>' \
  -Pandroid.testInstrumentationRunnerArguments.authHeaderName=key \
  -Pandroid.testInstrumentationRunnerArguments.authKeyB64="$KEYB64"
```
Use the n8n **production** `/webhook/` URL (repeatable), not the one-shot `/webhook-test/` URL.

## Security note
`android:usesCleartextTraffic="true"` is enabled so self-hosted **http** webhooks (common for a
LAN n8n) work. Notification content is sensitive — **prefer an https webhook**. The bearer token
and webhook URL are stored in `EncryptedSharedPreferences`. Use per-app/keyword exclusions and the
"capture body off" (metadata-only) option to avoid storing OTPs / banking messages.

## Build
```
./gradlew assembleDebug           # debug APK
./gradlew assembleRelease         # R8 full-mode + resource shrinking
```

## Test
```
./gradlew testDebugUnitTest       # JVM unit tests (redaction, payload, uploader, DAO logic)
./gradlew connectedDebugAndroidTest   # instrumented: Room DAO + full upload pipeline (needs a device/emulator)
```

## Configure on device
1. Open the app → **Grant notification access** and (recommended) **Ignore battery optimization**.
2. Set the **webhook URL** and **bearer token**, pick a trigger mode, Save.
3. Use **Sync now** to push immediately; **Purge now** to clear old uploaded rows.
