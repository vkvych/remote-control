# Remote Control

Parental remote control for Samsung Galaxy devices: a **parent app** that adjusts settings on a
**child device** running a companion **agent app**.

Phase 1 (this repository's current state) covers audio — volume per stream, ringer mode and a
silence-everything shortcut. The architecture is built so that app management and other device
kinds slot in without rework.

## How it fits together

```
┌─ Parent phone ───────────┐      tailnet or LAN      ┌─ Child phone / tablet ────┐
│ app-parent               │                          │ app-child                 │
│  Compose dashboard       │   ws:// + bearer token    │  Ktor server in a         │
│  OkHttp WebSocket client ├──────────────────────────►│  foreground service       │
│  DataStore: host + token │   JSON, versioned         │  AudioManager             │
└──────────────────────────┘                          │  DevicePolicyManager (P2) │
        Tailscale runs on both devices                 └───────────────────────────┘
        (WireGuard encryption, stable 100.x addresses)
```

| Module       | What it is                                                                        |
|--------------|-----------------------------------------------------------------------------------|
| `shared`     | Plain Kotlin/JVM. The wire protocol: commands, state, pairing DTOs, credentials.   |
| `app-child`  | The agent installed on the child's device. Hosts the control server.               |
| `app-parent` | The controller installed on the parent's phone.                                    |

`shared` has no Android dependencies on purpose, so a future controller for another device kind —
a Samsung TV bridge, a desktop client — reuses the same message types.

## Design decisions worth knowing

**Transport is direct, not cloud.** The parent app talks straight to the child device. Tailscale
supplies the "remote" part: both devices join the family tailnet and the child gets a stable
`100.x` address that works from anywhere. There is no backend to run, pay for, or trust with a
child's device.

**Encryption comes from the tailnet, authorisation from a token.** Traffic is plain `ws://`, which
is why the bearer-token check is unconditional: anything else on the LAN can reach the port. There
is no certificate to validate for a `100.x` address, so TLS here would be self-signed ceremony
rather than security.

**Pairing trades a short secret for a long one.** The child device displays a six-digit code; the
parent redeems it once for a 256-bit token. The code expires after five minutes and is burned after
five wrong guesses, so it cannot be ground down over the network. The agent stores only a SHA-256
hash of the token it issued.

**The agent pushes, it does not just answer.** It watches the device's own volume settings and
ringer mode, so a child using the hardware keys shows up on the parent's sliders immediately.

## Getting started

Build both APKs and install them:

```bash
./gradlew :app-child:assembleDebug :app-parent:assembleDebug
```

Then follow [docs/SETUP.md](docs/SETUP.md) for Tailscale, permissions, pairing, and the Device
Owner provisioning that Phase 2 needs.

## Roadmap

- **Phase 1 — audio.** Volume per stream, ringer mode, silence-everything. *Current.*
- **Phase 2 — app management.** Requires Device Owner: hide, suspend and silently uninstall apps,
  block installs, lock the device. The `DeviceAdminReceiver` already ships so no reprovisioning is
  needed.
- **Phase 3 — Knox.** Samsung-only extras where `DevicePolicyManager` falls short, behind an
  optional license. Phases 1 and 2 must keep working without it.
- **Phase 4 — more devices.** Samsung TV and friends, as new clients speaking the same protocol.
