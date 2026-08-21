# Setup

Walkthrough for getting the two apps running on real devices. Roughly 30 minutes, most of it
waiting for downloads.

You need: the child's Galaxy phone or tablet, the parent's phone, a USB cable and a computer with
`adb` — the cable is only needed if you want Device Owner (Phase 2 features).

---

## 1. Build the APKs

### Prerequisites

You build on your computer, not the phones. You need:

- **JDK 17.** The Gradle wrapper is pinned to 8.14.3, which runs on Java 17–24 only — a newer JDK
  (21+, and certainly 26) fails with an opaque version error, and the modules compile against a
  Java 17 toolchain regardless. Eclipse Temurin 17 is the safe choice. A tidy, tool-friendly home
  is `~/.jdks/` (IntelliJ's convention, and Gradle scans it for toolchains):

  ```bash
  # Linux x64 example — adjust the URL for your OS/arch from https://adoptium.net
  mkdir -p ~/.jdks && cd ~/.jdks
  curl -sSL -o temurin17.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
  tar xzf temurin17.tar.gz && rm temurin17.tar.gz
  export JAVA_HOME="$HOME/.jdks/$(ls ~/.jdks | grep '^jdk-17')"
  ```

- **Android SDK** with the API 36 platform and build-tools 36.0.0 (the apps target `compileSdk 36`).
  Android Studio installs these for you; on a headless machine use the command-line tools:

  ```bash
  export ANDROID_HOME="$HOME/Android/Sdk"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  # Download commandlinetools-linux-<latest>_latest.zip from
  # https://developer.android.com/studio#command-line-tools, unzip it, then:
  #   mv cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
  SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  yes | "$SDKMANAGER" --licenses
  "$SDKMANAGER" "platform-tools" "platforms;android-36" "build-tools;36.0.0"
  ```

  Point Gradle at the SDK with either the `ANDROID_HOME` environment variable (above) or a
  `local.properties` file in the repo root — `sdk.dir=/absolute/path/to/Android/Sdk`.
  `local.properties` is gitignored; keep it out of commits.

With `JAVA_HOME` and `ANDROID_HOME` exported (put them in your shell profile to make them stick):

```bash
./gradlew :app-child:assembleDebug :app-parent:assembleDebug
```

Outputs:

- `app-child/build/outputs/apk/debug/app-child-debug.apk` → the child's device
- `app-parent/build/outputs/apk/debug/app-parent-debug.apk` → the parent's phone

Install over USB with `adb install -r <apk>`, or copy the file to the device and open it (you will
have to allow installing from that source once).

> Debug builds are signed with the local debug key, which is fine for sideloading but means the two
> apps cannot be updated across machines. If you plan to keep these long term, generate a keystore
> and add a release signing config.

---

## 2. Install Tailscale on both devices

Without Tailscale everything still works while both devices are on the same Wi-Fi. Tailscale is
what makes it work from anywhere.

1. Install the Tailscale app from Play Store on **both** devices and sign in to the same account.
2. On the child's device, enable Tailscale's **always-on VPN** so it reconnects after a reboot:
   *Settings → Network → VPN → Tailscale → Always-on VPN*.
3. Note the child device's Tailscale address (`100.x.y.z`) — the agent app displays it for you in
   step 3, so there is no need to hunt for it here.

Optionally enable MagicDNS in the Tailscale admin console and use the device's name instead of its
IP address; the parent app accepts either.

---

## 3. Set up the agent on the child's device

Open **Remote Control Agent** and work down the screen:

1. **Start agent.** The status card turns green and lists the addresses the device is reachable on.
   The Tailscale address is shown first and in bold — that is the one to use.
2. **Grant the three permissions.** Each has a button that opens the right system screen:

   | Permission | Why it matters |
   |---|---|
   | Notifications | Android requires it for the foreground service notification to be visible. |
   | Do Not Disturb access | Without it, changing ring and notification volume fails whenever DND is on — exactly when you are most likely to reach for it. |
   | Unrestricted battery | Stops One UI putting the agent to sleep and leaving the device unreachable. |

3. On Samsung specifically, also check *Settings → Battery → Background usage limits* and make sure
   the agent is **not** in "Sleeping apps" or "Deep sleeping apps". One UI is more aggressive than
   stock Android here, and a deep-sleeping agent is an unreachable agent.

---

## 4. Pair

1. On the child's device, tap **Show pairing code**. A six-digit code appears with a countdown.
2. On the parent's phone, open **Remote Control**:
   - Enter the child's Tailscale address (or MagicDNS name) and leave the port at `8765`.
   - Tap **Check connection**. It should report the child device's name — if it does not, the
     address is wrong or Tailscale is down on one of the devices. Fix that before going further.
   - Enter the six-digit code and tap **Pair**.
3. The dashboard appears with live volume sliders.

The code is valid for five minutes, is single-use, and is refused after five wrong attempts. If any
of that happens, just show a new one.

**To re-pair** (new parent phone, or the app was reinstalled): show a new code and pair again. That
replaces the old token and immediately locks out the previous parent device.

---

## 5. Check it works

Worth doing all four — each catches a different failure:

1. **Same network.** Move a slider on the parent phone; the child's volume changes.
2. **Away from home.** Turn Wi-Fi off on the parent phone so it is on mobile data. With Tailscale
   up on both, the sliders keep working.
3. **Push updates.** Press the hardware volume keys on the child's device; the parent's sliders
   follow within a second.
4. **Reboot.** Restart the child's device and, without opening anything, confirm the parent app
   reconnects on its own. This is the one that catches battery-optimisation problems.

---

## 6. Device Owner provisioning (needed for Phase 2)

Volume control does **not** need this. Hiding, suspending and uninstalling apps does.

Device Owner can only be set on a device with **no accounts configured**, so the natural moment is
right after a factory reset — before signing into Google or Samsung. Doing it later means removing
every account first, and if that is not possible, a factory reset.

```bash
# 1. Enable Developer options and USB debugging on the child's device.
# 2. Remove every account (Settings → Accounts) if the device is not freshly reset.
# 3. Install the agent APK.
adb install -r app-child/build/outputs/apk/debug/app-child-debug.apk

# 4. Provision.
adb shell dpm set-device-owner com.vkvych.remotecontrol.child/.admin.AdminReceiver
```

On success it prints `Success: Device owner set to package com.vkvych.remotecontrol.child`. The
agent's **Device Owner** card flips to "Provisioned". You can sign back into accounts afterwards.

Common failure: `java.lang.IllegalStateException: Not allowed to set the device owner because there
are already several users on the device` — an account or secondary user is still present.

Once provisioned, the agent cannot be uninstalled by the child and is exempt from the background
restrictions that would otherwise starve it.

---

## 7. Samsung Knox (Phase 3, optional)

Only needed for capabilities `DevicePolicyManager` does not cover, such as blocking safe-mode boot.
Nothing in Phases 1 and 2 depends on it.

1. Register at the [Samsung Knox partner portal](https://www.samsungknox.com/).
2. Generate a **KPE Development** license key. It is free, limited in device count and expires, so
   plan on renewing it.
3. Add the key to the agent and activate it via `KnoxEnterpriseLicenseManager` at runtime.

---

## Testing on emulators

You can exercise the whole pairing flow on two Android emulators, no physical devices needed.
This is the fastest way to smoke-test a change end to end.

**Extra prerequisite: hardware virtualization.** The x86_64 emulator needs KVM on Linux
(`/dev/kvm` must exist). Check with `grep -c vmx /proc/cpuinfo` — a `0` means Intel VT-x (or AMD-V)
is disabled in your UEFI/BIOS. Enable *Intel Virtualization Technology / VT-x* in firmware, reboot,
then confirm `/dev/kvm` appears and add yourself to the `kvm` group (`sudo usermod -aG kvm "$USER"`).

The helper script does the whole setup:

```bash
export JAVA_HOME="$HOME/.jdks/temurin-17.0.20"   # a JDK 17
export ANDROID_HOME="$HOME/Android/Sdk"
scripts/run-emulators.sh
```

It installs the emulator and the API 36 system image, creates two AVDs (`rc_child`, `rc_parent`),
boots both headless, builds and installs the two APKs, and bridges their networks. Tear it all down
with `scripts/run-emulators.sh --stop`.

### Why the network needs bridging

Each emulator sits behind its own NAT, so the two guests cannot reach each other directly. The
script bridges them through the host loopback:

```
child guest :8765  --adb forward-->  host 127.0.0.1:8765
parent  --> 10.0.2.2:8765 --> host loopback --> child guest :8765
```

`10.0.2.2` is the emulator's built-in alias for the host loopback, and `adb forward` publishes the
child's agent port there. So when the script finishes, pair the parent against **host `10.0.2.2`,
port `8765`**.

### The one manual step

The agent only opens its server after you tap **Start agent**, and pairing needs the six-digit code
it shows — so finish by hand once the emulators are up:

1. On the **child** emulator: open *Remote Control Agent* → **Start agent** → **Show pairing code**.
   Sanity-check the server from the host with `curl -s http://127.0.0.1:8765/health`.
2. On the **parent** emulator: open *Remote Control* and pair with host `10.0.2.2`, port `8765`, and
   that code.

Grant Device Owner for Phase 2 features on the emulator with
`adb -s emulator-5554 shell dpm set-device-owner com.vkvych.remotecontrol.child/.admin.AdminReceiver`
(only on a device with no accounts added).

---

## Troubleshooting

**"Could not reach an agent at ..."**
Check the agent is started and shows a green status. Confirm the address matches what the agent
displays. If you are away from home, confirm Tailscale is connected on *both* devices.

**Sliders work at home but not away.**
Tailscale is not up on one of the devices, or always-on VPN is off on the child's device so it
dropped off the tailnet after a reboot.

**Ring volume will not change, media volume will.**
Do Not Disturb is on and the agent lacks Do Not Disturb access. The parent app shows a warning card
when this is the case.

**The parent app stops reconnecting after a while.**
Almost always One UI battery management. Check both "Unrestricted battery" in the agent and
*Settings → Battery → Background usage limits* on the child's device.

**The agent disappears after a reboot.**
It only auto-starts once paired. If it is paired and still does not come back, that is a battery
optimisation problem — see above.
