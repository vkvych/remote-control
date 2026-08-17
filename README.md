# Remote Control

Parental remote control for Samsung Galaxy devices: a **parent app** that adjusts settings on a
**child device** running a companion **agent app**.

The intent is a system a parent can actually reach from anywhere — turn the volume down on a
tablet in another room, silence a phone at bedtime — without routing a child's device through
somebody else's cloud.

## Approach

- **Two apps.** A controller on the parent's phone, an agent on the child's device.
- **Direct transport.** The parent app talks straight to the agent. Tailscale supplies the stable
  address and the encryption, so there is no backend to run, pay for, or trust.
- **Vendor APIs where they earn their place.** Public Android APIs first; Device Owner for app
  management, and Samsung Knox only for what `DevicePolicyManager` cannot do.

## Layout

| Module       | What it is                                                                      |
|--------------|---------------------------------------------------------------------------------|
| `shared`     | Plain Kotlin/JVM. The wire protocol, reusable by future non-Android clients.     |
| `app-child`  | The agent installed on the child's device. Hosts the control server.             |
| `app-parent` | The controller installed on the parent's phone.                                  |

## Roadmap

1. **Audio** — volume per stream, ringer mode, silence-everything.
2. **App management** — hide, suspend and uninstall apps. Requires Device Owner.
3. **Knox** — Samsung-only extras, behind an optional license.
4. **More devices** — Samsung TV and friends, speaking the same protocol.

## Status

Early. Phase 1 is being built on `claude/android-remote-control-app-crk744`; see that branch's
`README.md` and `docs/SETUP.md` for architecture detail and device setup.

## Building

Requires JDK 17+ and the Android SDK (API 36). Android Studio supplies both.

```bash
./gradlew :app-child:assembleDebug :app-parent:assembleDebug
```
