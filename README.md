# Device Tree — UMIDIGI G7 Tab Pro (codename: `g7tabpro`)

TWRP/OrangeFox device tree for the UMIDIGI G7 Tab Pro (MediaTek Helio G99 /
MT6789, Android 13, 8GB/256GB, 11" 1200x1920). Built from scratch — no
public device tree existed for this tablet before this one.

## Current status

- ✅ **Builds successfully** against the `twrp-12.1` minimal manifest (`lunch twrp_g7tabpro-eng && mka vendorbootimage`)
- ✅ **Boots to the TWRP main menu.** Graphics render correctly, all partitions mount, the UI is fully reached.
- ⚠️ **Touchscreen does not work yet** — the panel is a Chipone TDDI controller (`tddi_9551.ko`); the driver is included but not yet binding. See [Remaining issue: touch](#remaining-issue-touch).
- ⚠️ **`/data` decryption is disabled** — FBE decryption hung the boot (unresolvable keymaster version), so crypto flags were removed from the `/data` fstab entry. `/data` shows as unmountable in TWRP. This is an intentional trade-off: flashing ROMs writes to system/super, which doesn't need `/data`.
- Recovery is usable over `adb sideload` for flashing; on-device use awaits the touch fix.

If you're picking this project up: TWRP boots and the remaining work is the
touchscreen. The single most useful tool is `adb` into the running recovery —
it comes up as `recovery` in `adb devices` during boot, and
`adb shell "/system/bin/recovery 2>&1"` plus `/tmp/recovery.log` are how every
boot issue below was diagnosed. See [Remaining issue: touch](#remaining-issue-touch).

## Confirmed hardware/partition facts

All of the following were reverse-engineered directly from the stock
firmware (scatter file, `boot.img`, `vendor_boot.img`, `dtbo.img`, real
`fstab.mt6789` pulled from a rooted unit) — not guessed:

- **No physical `/recovery` partition** — boot-as-recovery model via `vendor_boot`, not a dedicated recovery partition.
- **A/B device with dynamic partitions** — every partition has `_a`/`_b`, and `system`/`vendor`/`product`/`vendor_dlkm`/`odm_dlkm` live inside a `super` partition (~9GiB), not as standalone partitions.
- **No `/cache` partition** exists.
- **Boot image header v4**, GKI-style split: `boot.img` = kernel + generic ramdisk, `vendor_boot.img` = vendor ramdisk + DTB (dt-table-wrapped, single entry), `init_boot` partition exists in the scatter file but is *not* actually used by the real fstab — `boot.img` still carries a full ramdisk itself.
- **Kernel cmdline**: `bootopt=64S3,32N2,64N2` — independently confirmed via two different real MT6789 device trees, not just our own extraction.
- **Kernel offsets** (relative to `BOARD_KERNEL_BASE := 0x3fff8000`): kernel `0x00008000`, ramdisk `0x26f08000`, tags `0x07c88000`, dtb `0x07c88000`.
- **`vendor_boot`'s ramdisk table has 2 fragments** in every working reference examined: a small `platform`-type fragment (early first-stage boot tools only — `e2fsck`, `linker64`, `snapuserd`, AVB GSI pubkeys) and a much larger `recovery`-type fragment (everything else — TWRP binary, `twres/`, kernel modules, HAL services). Our build now produces this correctly, but the platform/recovery split for *our own* first_stage_ramdisk content and kernel modules doesn't yet exactly match the working reference (ours still places kernel modules in the platform fragment; the working reference places them in recovery). Worth checking first if picking this back up.
- **Storage bus**: the scatter file defines both eMMC and UFS sections with identical partition sizes; the real ramdisk carries both `fstab.emmc` and `fstab.mt6789`, confirming genuine runtime storage-type detection rather than a stale template.
- **Kernel version**: 5.10.185 (confirmed).
- **Codename**: `ro.product.vendor.device=G7_Tab_Pro` (read live off a rooted unit — authoritative since `/vendor` isn't touched by flashing a GSI). The `g7tabpro` build codename used throughout this tree doesn't need to match this exactly; they're separate namespaces.
- **Security patch level**: 2024-10-05 (stock).

## Kernel: no source, prebuilt path

No GPL kernel source release exists for this device (checked UMIDIGI's
community forum — only SPFT firmware packages are posted). `BoardConfig.mk`
uses real binaries extracted from stock firmware instead, staged in
`prebuilt/`:

- `Image.gz` — exact gzip-compressed kernel as embedded in stock `boot.img` (decompresses to a verified valid ARM64 kernel)
- `dtb/` — base hardware device tree, extracted from `vendor_boot.img`'s dt-table-wrapped dtb section
- `dtbo.img` — stock file, used as-is
- `modules/` — 175 real `.ko` kernel modules extracted from the stock vendor ramdisk (clocks, charger, `cfg80211.ko`/Wi-Fi stack, etc.), plus `modules.load`/`modules.load.recovery`/`modules.dep`
- `first_stage_ramdisk/` — early-boot binaries (`e2fsck`, `linker64`, `snapuserd` + libs, AVB GSI pubkeys) extracted from a working reference build, since these can't be built from source in the TWRP minimal manifest

Trade-off: no kernel patching (no KernelSU, no bug fixes) unless UMIDIGI
provides source on request.

## How the boot was fixed (resolved)

The build initially appeared to "hang" at the UMIDIGI splash. It was actually
a chain of separate issues, each found and fixed in turn (full history in git
log). The ones that mattered:

1. **Missing `libresetprop.so`** — the recovery binary failed to link and
   exited status 1 on every init restart (a 5-second loop that looked like a
   hang). Fixed with `TW_INCLUDE_RESETPROP := true` + packaging the library.
   This was *the* blocker — TWRP never ran a single instruction before it.
2. **Wrong pixel format** — TWRP fell back to RGB565 and segfaulted
   (`SIGSEGV`/`SEGV_ACCERR`) the instant it drew the splash. Fixed with
   `TARGET_RECOVERY_PIXEL_FORMAT := "RGBX_8888"` (matches the working MT6789
   reference; the panel expects 32-bit).
3. **FBE decryption hang** — TWRP reached the splash then hung permanently
   trying to decrypt `/data`; the keymaster version was unresolvable because
   `/vendor` isn't mounted that early. Fixed by removing the `fileencryption=`
   / `keydirectory=` flags from the `/data` entry in the fstab that
   `TARGET_RECOVERY_FSTAB` actually uses (`rootdir/etc/fstab.mt6789`).

Earlier structural fixes (fragment split, first_stage fstab placement, kernel
module load-list separation, MT6789 init scripts) were all real and necessary
groundwork, but none was the thing keeping TWRP from running — that was #1.

**Debugging note for anyone continuing this:** the recovery environment comes
up with `adb` (shows as `recovery` in `adb devices`) *during boot*, which is
how the above were diagnosed — `adb shell "/system/bin/recovery 2>&1"` prints
exactly why the binary exits, and `/tmp/recovery.log` (once TWRP runs far
enough) shows where it stops. At the main *menu*, TWRP may switch USB to MTP
and `adb` can drop; if it does, reassign the Windows driver to "Android ADB
Interface" in Device Manager.

## Remaining issue: touch

The panel is a **Chipone TDDI** controller (touch+display combined). The
driver `tddi_9551.ko` was pulled from stock `/vendor/lib/modules/` and is
included in the recovery module set (`prebuilt/modules/`, added to
`modules.load.recovery` after its deps `panel-dk068h5gq-dsi-vdo` and
`mtk_disp_notify`). Confirmed facts:

- The `.ko` has the **same vermagic** as the 171 modules that load
  successfully, so vermagic is *not* the blocker (this was checked and ruled
  out).
- The device-tree `touch_panel` node reports `compatible = "goodix,touch"`,
  while the driver's alias binds to `chipone_tddi` — a likely mismatch, but
  unconfirmed as the cause.
- On the last testable build, `getevent -p` showed only the two button input
  devices; no touchscreen. Whether `tddi_9551` loads-but-doesn't-bind vs.
  doesn't-load-at-all was not confirmed on the final build (adb access was
  lost at the menu before this could be checked).

Next steps for touch: with `adb` working at the menu, run
`lsmod | grep tddi`, `dmesg | grep -iE 'tddi|chipone|touch'`, and
`getevent -p`. If it loads but doesn't bind, the fix is aligning the DT
compatible (`goodix,touch` vs `chipone_tddi`) — likely via a DTBO overlay
that isn't currently applied in recovery. If it doesn't load, check its
dependency/probe order.

## Donor/reference trees used

- [`MT6789-Rock/device_xiaomi_rock`](https://github.com/MT6789-Rock/device_xiaomi_rock) — Redmi 11 Prime 4G / POCO M5, same MT6789 + Mali-G57 MC2 platform. Independently confirmed boot header v4, page size, and kernel cmdline; caught the kernel offset bug.
- [`transsion-mt6789-recovery/twrp-device_tecno_TECNO-LI7`](https://github.com/transsion-mt6789-recovery/twrp-device_tecno_TECNO-LI7) + its shared [`mt6789-common`](https://github.com/transsion-mt6789-recovery/twrp-device_transsion_mt6789-common) config — TECNO POVA 6, same platform, confirmed **working Display + Decryption** on OrangeFox. Source of most of the `BoardConfig.mk` flags fixed above (`BOARD_INCLUDE_RECOVERY_RAMDISK_IN_VENDOR_BOOT`, `BOARD_AVB_ENABLE`, the anti-rollback hack, etc.)

## Building

```bash
mkdir ~/twrp && cd ~/twrp
repo init --depth=1 -u https://github.com/minimal-manifest-twrp/platform_manifest_twrp_aosp.git -b twrp-12.1
repo sync

mkdir -p device/umidigi
cp -r /path/to/device_umidigi_g7tabpro device/umidigi/g7tabpro

export ALLOW_MISSING_DEPENDENCIES=true
. build/envsetup.sh
lunch twrp_g7tabpro-eng

# NOT "mka recoveryimage" — no /recovery partition exists on this device.
mka vendorbootimage
```

A GitHub Actions workflow is included (`.github/workflows/build-twrp.yml`)
if you'd rather build in CI than locally — same steps, automated, with
disk-cleanup/swap-increase steps included for GitHub's hosted runners.

**Flashing**: the build target is `vendorbootimage`, so the output flashes
to the `vendor_boot` partition, not `recovery`:
```
fastboot flash vendor_boot vendor_boot.img
```
No slot suffix needed — this device uses unsuffixed by-name paths with
the `slotselect` fstab flag.

## Repo layout

- `Android.mk`, `AndroidProducts.mk`, `lineage_g7tabpro.mk` — full ROM build entry points (untested — recovery bring-up was the focus so far)
- `twrp_g7tabpro.mk` — TWRP-specific product makefile (deliberately standalone, doesn't inherit `device.mk`)
- `device.mk` — package/HAL inheritance for a full ROM build
- `BoardConfig.mk` — target config; heavily commented with the reasoning behind non-obvious values
- `rootdir/etc/fstab.mt6789` — the real device fstab, pulled from a rooted unit
- `prebuilt/` — kernel, DTB, DTBO, kernel modules, first-stage binaries (see Kernel section above)
- `recovery.fstab` — an earlier hand-adapted TWRP-style fstab, superseded by `rootdir/etc/fstab.mt6789` but kept for reference
- `extract-files.sh`, `proprietary-files.txt` — extract-utils scaffold for a future full ROM build (not yet run against a real device dump)

## Next steps for a full LineageOS/AOSP build

Not yet attempted — recovery bring-up was step one. Once the boot hang
above is resolved: run `extract-files.sh` against a rooted device or
mounted stock firmware to populate `proprietary-files.txt` (don't hand-write
it), then attempt `lunch lineage_g7tabpro-userdebug`.
