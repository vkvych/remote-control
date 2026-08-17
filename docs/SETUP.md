# Setup

Walkthrough for getting the two apps running on real devices. Roughly 30 minutes, most of it
waiting for downloads.

You need: the child's Galaxy phone or tablet, the parent's phone, a USB cable and a computer with
`adb` — the cable is only needed if you want Device Owner (Phase 2 features).

---

## 1. Build the APKs

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
