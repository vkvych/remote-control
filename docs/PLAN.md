> **Historical document — the plan agreed before any code was written.** Kept for the reasoning
> behind the architecture and the alternatives weighed along the way, which
> [README.md](../README.md) states as decisions without arguing for. Passages written in the
> future tense describe work that has since been built.
>
> One part did not survive contact with the environment: the Android modules could not be
> compiled where this plan was executed, because `dl.google.com` is blocked there and with it the
> Android SDK and all AndroidX/AGP artifacts. `:shared` and `PairingSession` were verified
> instead; the first real Gradle run has to happen on a machine with the SDK.
>
> For device setup see `docs/SETUP.md`.

# Android Remote Control (Parent → Child device)

## Context

Personal parental-control system for Samsung Galaxy devices (latest One UI / Android 16, API 36): a **parent app** remotely controls a **child app** installed on the kid's phone/tablet — starting with volume control, later app hiding/uninstalling, and eventually other device types (Samsung TV). The repo (`vkvych/remote-control`) is empty; this is a greenfield build on branch `claude/android-remote-control-app-crk744`.

**Agreed decisions:**
- **Transport:** Direct device-to-device over the local network, with **Tailscale** providing the "remote" reach (both devices join the user's tailnet; the child app's server is reachable via its stable Tailscale IP/MagicDNS name from anywhere). No cloud backend to build.
- **Privileges:** Child app becomes **Device Owner** (one-time ADB provisioning) + **Samsung Knox SDK** for vendor extras.
- **Distribution:** Sideloaded APKs (no Play Store constraints).
- **Stack:** Kotlin monorepo — `:app-parent`, `:app-child`, `:shared`.

## Architecture

```
┌─ Parent phone ──────────┐        tailnet / LAN         ┌─ Child phone ─────────────┐
│ :app-parent (Compose UI)│  WebSocket (JSON, token-auth)│ :app-child                │
│  DeviceRepository ──────┼──────────────────────────────┼→ Ktor embedded server     │
│  stores host + token    │                              │  in a Foreground Service  │
└─────────────────────────┘                              │  → AudioManager (volume)  │
        Tailscale app runs on both devices               │  → DevicePolicyManager    │
        (WireGuard encryption, stable 100.x IPs)         │  → Knox SDK (later)       │
                                                         └───────────────────────────┘
```

- **Protocol** lives in `:shared`: sealed `Command` / `Response` / `DeviceState` classes serialized with `kotlinx.serialization` over a WebSocket. Versioned (`protocolVersion` field) so parent/child can evolve independently. This module stays platform-agnostic (pure Kotlin) so a future TV controller or desktop client can reuse it.
- **Child server:** Ktor CIO embedded server on fixed port (e.g. `8765`), hosted in a foreground service (`foregroundServiceType="specialUse"`), auto-started on boot (`RECEIVE_BOOT_COMPLETED`). Tailnet traffic is already WireGuard-encrypted, so plain `ws://` is fine, but every request must carry the pairing token (protects against anything else on the LAN/tailnet).
- **Pairing/auth:** during setup the child app shows a one-time 6-digit code; the parent app sends it and receives a long random persistent token; both sides store it (DataStore on parent, EncryptedSharedPreferences on child). All subsequent WS sessions authenticate with this token.
- **Discovery:** parent app stores the child's Tailscale MagicDNS hostname or IP, entered once during pairing. Optional convenience later: NSD/mDNS auto-discovery when on the same physical LAN.

## Repository layout

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml (version catalog)
shared/          – pure Kotlin: protocol models, serialization, constants
app-child/       – agent app: Ktor server, ControlService (FGS), DeviceAdminReceiver,
                   command handlers (VolumeHandler, later AppsHandler/KnoxHandler),
                   minimal setup UI (pairing code, permissions checklist, status)
app-parent/      – controller app: Compose UI (device card, volume sliders, status),
                   WebSocket client (OkHttp or Ktor client), DataStore for paired devices
docs/            – SETUP.md (Tailscale + Device Owner provisioning + Knox licensing steps)
```

- Kotlin 2.x, AGP current stable, `compileSdk/targetSdk = 36` (Android 16 / One UI 8), `minSdk = 31` (covers all recent Galaxy devices; nothing we need requires higher).
- Jetpack Compose + Material 3 in both apps; child app UI is deliberately minimal.

## Phases

### Phase 1 — MVP: remote volume control (this implementation)
1. **Project skeleton:** Gradle multi-module setup, version catalog, both apps building empty-shell APKs.
2. **`:shared` protocol:** `Command.GetState`, `Command.SetVolume(stream, level)`, `Command.SetRingerMode(mode)`; `DeviceState` (per-stream volume/max, ringer mode, battery, deviceName); auth handshake messages.
3. **`:app-child`:**
   - `ControlService` (foreground service, persistent notification) hosting Ktor WebSocket server; boot receiver.
   - `VolumeHandler` using `AudioManager` (`setStreamVolume` for MUSIC/RING/ALARM/NOTIFICATION/SYSTEM). Request **Do Not Disturb access** (`ACCESS_NOTIFICATION_POLICY`) in the setup checklist — required to change ring volume while DND is on.
   - Setup screen: service status, pairing-code flow, permissions checklist (notifications, DND access, battery-optimization exemption).
   - `AdminReceiver : DeviceAdminReceiver` + `device_admin.xml` already included now, so Device Owner provisioning (Phase 2) needs no reinstall — DO can only be set on a fresh-enough device, so shipping the receiver in the MVP APK matters.
4. **`:app-parent`:** pairing screen (host + code entry), device dashboard (connection status, volume sliders per stream, ringer mode toggle), auto-reconnecting WebSocket client, state refresh on reconnect.

### Phase 2 — Device Owner: app management
- Provisioning is manual, documented in `docs/SETUP.md`: `adb shell dpm set-device-owner com.vkvych.remotecontrol.child/.admin.AdminReceiver` (requires no Google accounts on the device at that moment — remove, provision, re-add).
- New commands: `ListApps`, `HideApp`/`UnhideApp` (`DevicePolicyManager.setApplicationHidden`), `SuspendApp` (`setPackagesSuspended`), `UninstallApp` (silent, via `PackageInstaller.uninstall` — works silently for a Device Owner), `BlockInstalls` (`DISALLOW_INSTALL_APPS` user restriction), `LockNow`.
- Parent UI: installed-apps list with per-app actions.
- As DO, the child app self-grants its runtime permissions via `setPermissionGrantState` and becomes non-removable by the child.

### Phase 3 — Knox SDK extras
- Prerequisite (user action): register at the Samsung Knox partner portal and obtain a **KPE Development license key** (free, but device-count-limited and expiring — fine for family use, needs periodic renewal). Key is activated at runtime via `KnoxEnterpriseLicenseManager`.
- Adds Samsung-only capabilities on top of Device Owner where DPM falls short: prevent safe-mode boot (so the child can't bypass the agent), firmware/OTA controls, deeper app-disable options via Knox `ApplicationPolicy`.
- Implemented as an optional `KnoxHandler` — the app must keep working without a Knox license so Phase 1–2 features never depend on it.

### Phase 4 — Future (not in scope now, but shaping decisions above)
- More controls: screen time limits, geofencing/location, website filtering.
- Other device types (Samsung TV via its local REST/WebSocket API): new client modules speaking the same `:shared` protocol pattern; parent app gets a device-type abstraction from day one (`DeviceKind` enum in the paired-device record).

## Key risks / notes
- **Foreground service longevity:** Android 14+ requires declared FGS types; use `specialUse` with a manifest property. Setup checklist asks for battery-optimization exemption; once DO (Phase 2), the app is much harder for the OS/child to kill.
- **Boot start on Android 15/16:** starting an FGS from `BOOT_COMPLETED` is allowed for `specialUse`; verify on the actual Galaxy device.
- **Tailscale is a prerequisite, not code:** both devices run the Tailscale app (always-on VPN on the child device). Documented in SETUP.md. Everything also works on plain shared Wi-Fi without Tailscale.
- **Device Owner provisioning is one-shot:** if it ever must be redone, accounts must be removed again. Ship the `DeviceAdminReceiver` in the very first APK (done in Phase 1).

## Verification
- **In this environment (no emulator/devices):** `./gradlew build` — compiles all modules, runs unit tests. Unit tests cover the `:shared` protocol (serialization round-trips, version handling) and the pairing/token logic with fakes.
- **On real hardware (user):** install both APKs; pair over shared Wi-Fi; move parent phone to mobile data with Tailscale up and confirm volume control still works; reboot child device and confirm the service self-starts; toggle DND and confirm ring-volume changes still apply.

## Implementation order for Phase 1 (the work to do now)
1. Gradle skeleton (root build files, version catalog, three modules, `.gitignore`, CI-friendly wrapper) — commit.
2. `:shared` protocol + unit tests — commit.
3. `:app-child`: service + Ktor server + volume handler + pairing + setup UI + admin receiver — commit.
4. `:app-parent`: client + pairing + dashboard UI — commit.
5. `docs/SETUP.md` (Tailscale, sideloading, DO provisioning for Phase 2 readiness) — commit, push branch.