# Booting a GSI on the UMIDIGI G7 Tab Pro — install guide

These images are **pre-patched** so the device's TrustKernel TEE accepts them. An
unmodified Android 14+ GSI hangs on the boot splash forever on this tablet; see
`KEYMINT_OS_VERSION_FIX.md` for why, and `patch-gsi-keymint.sh` to patch any other GSI
yourself.

Nothing here touches `/system`, `/data`, or any physical partition. It all runs through
DSU, which is reversible with a reboot.

---

## What's in this package

| File | What it is |
|---|---|
| `lineage-21-osver13.img.gz` | LineageOS 21 (Android 14), pre-patched — **verified booting** |
| `lineage-22-osver13.img.gz` | LineageOS 22.2 (Android 15), pre-patched — **verified booting** |
| `INSTALL.md` | this file |

Both keep their real API level (34 / 35) — only the version string reported to the TEE is
changed, so the framework still behaves as its true Android version.

---

## Requirements

- **Bootloader unlocked** — check with `fastboot getvar unlocked` (the `ro.boot.flash.locked`
  property lies if Magisk/TrickyStore is installed).
- **DSU Sideloader** — https://github.com/VegaBobo/DSU-Sideloader
- **~5 GB free** on internal storage. DSU writes the image to `/data`.
- A working ROM to install from (the stock A13 ROM or LineageOS 20 is fine).

---

## Install

1. Copy the `.img.gz` to the tablet (internal storage — an SD card may be rejected as
   "insufficient storage" even when it has room).
2. Open **DSU Sideloader** → select the `.img.gz` → **Install**.
3. Leave userdata size at the default (2 GB is plenty for testing).
4. Wait for `Installation finished successfully`, then reboot when prompted.

**First boot takes several minutes** — it is creating and encrypting a fresh userdata.
Do not assume it has hung until you have given it a genuinely patient wait.

To get back to your normal ROM: **reboot**. DSU is not sticky; a normal restart returns you
to the installed system. To re-enter the GSI, use the notification DSU leaves behind.

---

## Verifying it worked

```sh
adb shell getprop sys.boot_completed        # 1
adb shell "mount | grep ' /data '"          # a line, not empty
adb shell getprop init.svc.zygote           # running
adb shell getprop init.svc.pq-2-2           # running
```

The single most diagnostic line:

```sh
adb logcat -d | grep 'generateKey returned'
```

- `-67` → **good.** `ROLLBACK_RESISTANCE_UNAVAILABLE`; vold retries without that tag and
  succeeds. This is normal on this device.
- `-64` → the version fix is not in effect. `KEYMINT_NOT_CONFIGURED` — the TEE rejected the
  reported OS version.

---

## If it hangs

Where it stops tells you what went wrong:

| Symptom | Meaning |
|---|---|
| Hangs on the **UMIDIGI** splash, no adb | Failed before userspace. Usually the GSI is too far ahead of the vendor (API 31 / Android 12 here). Android 16 GSIs fail this way. |
| Hangs on the **GSI/LineageOS** splash, adb works | Got into userspace. Check `generateKey` above — if `-64`, the patch did not apply; if `-67`, something else is wrong and the logcat will show it. |
| Reboots straight back to your ROM | DSU auto-reverted after a failed boot attempt. Same causes as the first row. |

Recovery is always just a **forced reboot** (hold power). DSU never writes to your physical
partitions, so your installed ROM and data are untouched no matter how badly the GSI fails.

Capture a log for anything unexplained:

```sh
adb wait-for-device && adb logcat -v threadtime > gsi-boot.txt
```

Run that *before* rebooting into the GSI so it attaches as soon as adb comes up.

---

## Known-good / known-bad on this device

| GSI | Vendor gap | Result |
|---|---|---|
| LineageOS 20 (A13) | 1 generation | boots unpatched |
| LineageOS 21 (A14) | 2 generations | boots **with the fix** |
| LineageOS 22.2 (A15) | 3 generations | boots **with the fix** |
| LineageOS 23.x (A16) | 4 generations | fails early — instant DSU revert (tested before the fix existed; worth retrying) |
| PeterGSI (A17, phh) | 5 generations | boots — phh builds spoof the version themselves |

The vendor on this tablet is API 31 (Android 12). The further a GSI is from that, the more
likely it fails for reasons that have nothing to do with KeyMint.

---

## Patching a different GSI yourself

```sh
sudo ./patch-gsi-keymint.sh some-other-gsi.img.gz
```

Requires `avbtool`, the `fec` binary from an AOSP tree, and `e2fsprogs`. Override the target
values for a different device:

```sh
TARGET_RELEASE=12 TARGET_PATCH=2024-01-05 sudo ./patch-gsi-keymint.sh gsi.img.gz
```

Use whatever `ro.build.version.release` your *working* ROM reports — that is the value the
TEE accepts.
