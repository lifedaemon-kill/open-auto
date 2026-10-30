# Wireless Android Auto receiver

OpenAuto implements an independent AAP/WPP receiver for an ordinary Android head unit. The Android app has two entry points: Bluetooth / Wi-Fi Direct pairing and a manual phone-server diagnostic path. The implementation builds and has local protocol/TLS tests; real-phone/head-unit interoperability has not been verified. It must not yet be described as a tested ZLink replacement.

## Current implementation

- AAP framing, fragmentation, bounded reassembly (4 MiB per message / 8 MiB aggregate).
- TCP connect for the phone's developer server, and ownership transfer of a phone-initiated SocketChannel for wireless operation.
- Version negotiation, mutual TLS 1.2 using SSLEngine, clear AuthComplete on the same socket.
- Per-install RSA identity in AndroidKeyStore; explicit SHA-256 peer-certificate review and pinning.
- Encrypted discovery, channel open, heartbeat, navigation/audio focus and ByeBye.
- A media profile separate from the control-only diagnostic profile:
  - H.264, 800 × 480 at 30 fps, decoded to Surface with MediaCodec.
  - PCM media (48 kHz / 16-bit / stereo), system and speech (16 kHz / 16-bit / mono) through dedicated AudioTrack workers.
  - Touchscreen input, up to ten pointers, with letterboxing and coordinate conversion.
  - Optional microphone PCM (16 kHz / 16-bit / mono), advertised only when microphone access is enabled.
  - Night-mode sensor; no invented speed, parking or vehicle-state data.
- Media credits returned after bounded sink consumption. Microphone Open replies precede DATA; negotiated uplink credits, bounded backlog and stale-session retirement are enforced.
- One TLS/IO owner: rendering, capture and UI callbacks enqueue bounded outgoing work; silent incoming traffic does not block outgoing touch/ACK/microphone messages.
- Audio focus, foreground-only capture, cancellation, Surface recreation, session teardown and explicit reconnect.
- Touchscreen volume controls and an exit action on the projection screen.

## Wireless pairing without USB

1. On the head unit choose Android Auto → “Подключить телефон”.
2. Allow nearby-device permissions. On Android 12 and earlier Wi-Fi Direct requires location permission; Android 13+ uses NEARBY_WIFI_DEVICES. Android 17 additionally requests ACCESS_LOCAL_NETWORK.
3. OpenAuto creates a Wi-Fi Direct group, preferring 5 GHz where available, and binds projection TCP port 5288 to that group's address.
4. “Сопрячь телефон” opens the system Bluetooth discoverability prompt. Pair with the head unit and select it in Android Auto on the phone. Existing OEM hands-free pairing must remain available for calls.
5. An authenticated Bluetooth RFCOMM connection on Android Auto's service UUID carries WPP. The receiver supplies the actual temporary network credentials and projection endpoint; no USB or developer server is involved in this route.
6. The phone joins Wi-Fi and initiates the projection TCP connection. On first TLS use, review the certificate fingerprint. Confirmation saves the pin against the paired Bluetooth identity and requests a fresh connection.
7. “Разрешить микрофон” enables optional voice input for a new session. Permission denial leaves image, touch and playback available. Capture starts only when the phone asks to open the microphone.
8. Leaving the projection screen stops capture and the session, closes radio/socket listeners and removes only the Wi-Fi Direct group created by this receiver. A group owned by another application is never removed.

The standard Bluetooth adapter must be exposed to Android applications. Some Chinese head units use an external MCU/Bluetooth module; ordinary RFCOMM APIs cannot control that module. Those units need a documented OEM transport adapter. The head-unit model/firmware and stock Bluetooth application are needed to implement it.

The WPP implementation uses the legacy StartRequest / InfoRequest / InfoResponse exchange and ping responses. It does not advertise an unimplemented WPP-over-TCP reconnect endpoint, a fake HFP service, or a fabricated Bluetooth MAC address. Call/SCO audio remains the responsibility of the real OEM hands-free stack.

## Manual Wi-Fi development path

1. Put phone and head unit on the same reachable Wi-Fi network.
2. Enable Android Auto developer settings on the phone and start its Head Unit Server.
3. Open the manual diagnostics and enter the phone's numeric local IPv4 address.
4. “Подключить по IP” opens the media receiver on TCP 5277. “Проверить TLS” performs a short check; “Открыть сессию” retains a control-only connection.
5. Review the certificate fingerprint on first use. Manual pins are bound to the exact phone IP.
6. Stop the phone's developer server after testing.

A version reply, TLS completion or discovery response is never sufficient to show “Android Auto”: that status is set only after a decoded video frame reaches the output adapter.

## Trust and lifecycle

The client key remains in AndroidKeyStore; it is not embedded, exported or backed up. Unknown, changed and expired peer certificates are rejected. Confirmation is scoped to the selected manual endpoint or paired wireless identity. This is pinning, not manufacturer certification or proof of a unique phone identity; Android Auto implementations can share certificates. No process-wide TLS defaults are changed.

Wi-Fi credentials and captured audio are not persisted or logged. The projection Activity is private and runs only in the foreground. A recreated Surface reconnects using a new decoder/session; background playback/projection is not implemented. Teardown has bounded network/worker waits. TLS renegotiation is rejected.

## Validation and remaining device work

Run:
`:projection:testDebugUnitTest :mobile:assembleDebug :mobile:testDebugUnitTest`

Tests cover framing limits/truncation, version handling, real mutual TLS over loopback, peer trust, fragmented encrypted application messages, control lifetime/cancellation, incoming TCP sessions, idle-read duplex touch/media credits, service negotiation/PCM alignment, microphone ordering/credits/stale callbacks, and WPP framing/credentials/failures.

These checks do not exercise an Android hardware codec, AudioTrack, AudioRecord, OEM amplifier, Bluetooth radio, Wi-Fi group formation or a real Android Auto phone. Required device acceptance:
- Wireless pairing and certificate-review reconnect; actual maps/music/touch/microphone.
- H.264 decoder compatibility, audio timing and focus interruptions.
- Call audio through the stock HFP stack, including microphone ownership during a call.
- Bluetooth-only A2DP sink / AVRCP metadata and commands exported by the firmware.
- Backgrounding, resizing/rotation, Wi-Fi loss, sleep/wake, permission revocation and reconnect.

There is no universal third-party A2DP-sink / HFP-client implementation here. A local media session bridge is not itself a Bluetooth audio receiver. Completing unsupported OEM paths requires the actual firmware interface and device testing.

## References

Wire-format research: [Open Headunit revision a4f80ff](https://github.com/andreknieriem/open-headunit/tree/a4f80ff1b6aa6726c17e7385192cf23abe0d0e9f). That AGPL checkout is research-only and is neither compiled nor packaged; this module is an independent implementation.
Platform APIs: [Wi-Fi Direct](https://developer.android.com/develop/connectivity/wifi/wifip2p), [WifiP2pManager](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager), [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [SSLEngine](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/net/ssl/SSLEngine.html).
