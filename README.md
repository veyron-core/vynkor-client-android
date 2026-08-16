# vynkor-client-android

Android device-agent for the vynkor (formerly Veyron) plugin kernel: turns a
phone into a remote device whose capabilities register on a host kernel as
`{device_id}.{cap}` (D-14). Geo, battery, notifications, clipboard, contacts,
mic and speaker are callable from the host over WebSocket.

Design + decisions: kernel repo, `docs/ANDROID_DEVICE_AGENT.md` and
`docs/ANDROID_DEVICE_AGENT_RUST_CORE.md`. Build/experience notes:
`docs/D14_IMPLEMENTATION_NOTES.md`.

## Layout

```
rust/   the protocol engine (vynkor-agent-core): framing, frame-MAC, WS client,
        registration, per-capability routing. Pure protocol — no Android APIs.
app/    the Kotlin/Gradle Android app: foreground service, capability providers
        (BatteryManager, LocationManager, ClipboardManager, ContactsContract,
        AudioRecord/AudioTrack), NotificationListenerService, onboarding UI.
```

Rust = protocol, Kotlin = device I/O, UniFFI is the boundary. The core reuses
`veyron-wire` 0.2.3 (proto v1.6) verbatim — no reimplemented crypto.

## Build

Prereqs: Rust + Android targets, NDK, cargo-ndk, Android SDK (see
`docs/D14_IMPLEMENTATION_NOTES.md` §Tooling).

```bash
# Rust core: host-side unit tests
cd rust && cargo test && cargo clippy --all-targets -- -D warnings

# Android APK (builds the .so via cargo-ndk + generates UniFFI bindings)
export ANDROID_HOME=$HOME/.android-sdk   # unset ANDROID_SDK_ROOT if it differs
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Run against a host

1. Host kernel with `jwt_secret` (≥32 bytes), `tls: false` for a plain-WS LAN
   test (or pin the served cert), `bind: 0.0.0.0`, and the WS port allowed in the
   host firewall (UFW gotcha — see implementation notes).
2. Mint a device token bound to the same id the app will use:
   ```bash
   vyn token --config <host-config> mint \
     --device my-phone \
     --permissions "PERMISSION_IPC_SEND,PERMISSION_EVENT_PUBLISH,PERMISSION_AUDIO_STREAM" \
     --ipc-targets kernel --ttl-seconds 86400
   ```
3. In the app: Host URL `ws://<host-ip>:<port>`, Device ID `my-phone`
   (**must match the JWT `sub`**), Device JWT, Host jwt_secret. Connect.
4. Verify: `adb logcat -s vynkor` shows `registered on host
   plugin_id=my-phone.<cap>` × 7; `GET /devices` on the host lists the device
   `state: online`.

## Capabilities

| cap | direction | provider |
|---|---|---|
| `{id}.battery` | host→device request | `BatteryManager` |
| `{id}.geo` | request + device→host push | `FusedLocationProvider`/`LocationManager` |
| `{id}.notifications` | device→host event | `NotificationListenerService` |
| `{id}.clipboard` | both | `ClipboardManager` |
| `{id}.contacts` | host→device request | `ContactsContract` |
| `{id}.mic` | device→host stream | `AudioRecord` → host STT (PCM v1) |
| `{id}.speaker` | host→device stream | `AudioTrack` (PCM v1) |

License: MIT OR Apache-2.0.
