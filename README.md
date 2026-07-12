# Device Tree Skeleton — UMIDIGI G7 Tab Pro (placeholder codename: g7tabpro)

## Confirmed from your uploaded files (scatter file, boot.img, vendor_boot.img, dtbo.img, build.prop)

- **No physical `/recovery` partition** — boot-as-recovery model. TWRP must patch `boot.img`'s ramdisk directly.
- **A/B device with dynamic partitions** — every partition has `_a`/`_b`, and `system`/`vendor`/`product` live inside a `super` partition (9GiB), not as standalone partitions.
- **No `/cache` partition** exists.
- **Boot image header v4** (GKI-style split: boot.img = kernel + generic ramdisk, vendor_boot.img = vendor ramdisk + DTB, init_boot = separate generic-ramdisk partition).
- Real kernel base/offsets and cmdline are filled into `BoardConfig.mk` already.
- **Codename confirmed**: `ro.product.vendor.device=G7_Tab_Pro`, read live off the tablet. This is authoritative — `/vendor` isn't touched by flashing a GSI, so this reflects the real stock vendor identity regardless of what system image is currently running. The `g7tabpro` codename already used throughout this skeleton is kept as-is (LineageOS build codename and vendor identity string are separate namespaces — they don't need to match, and your currently-booting GSI with `ro.product.device=tdgsi_arm64_ab` is proof that mismatch is harmless).
- Security patch level: 2024-10-05. `ro.hardware.egl=meow` is a real (if oddly named) HAL suffix used by this build — not an error.

There is no existing public device tree for this tablet, so this is a
from-scratch skeleton, not a working tree. It's built from the known specs
(MediaTek Helio G99 / MT6789, Android 13, 8GB/256GB, 11" 1200x1920) and
standard LineageOS/MTK device-tree conventions. Every value marked `TODO`
is a placeholder and **must** be replaced with real data pulled from your
actual device/firmware or the build will fail or brick nothing will boot.

## Why this order matters

Bring up TWRP/OrangeFox **before** attempting a full ROM. Recovery only
needs boot/recovery/dtbo + a correct fstab, so it's the fastest way to
confirm your partition layout and kernel are right before you invest time
in a full LineageOS build.

## Step 1 — Confirm the real codename

Boot the tablet, connect via ADB, and run:
```
adb shell getprop ro.product.device
adb shell getprop ro.product.vendor.device
adb shell getprop ro.build.description
```
Rename every `g7tabpro` reference in this skeleton (folder name,
`TARGET_DEVICE`, `PRODUCT_DEVICE`, file paths) to match.

## Step 2 — Get the exact partition layout

MediaTek devices have many extra partitions (nvram, protect1/2, seccfg,
persist, md1img, etc.) beyond the usual boot/system/vendor. Two ways to get
the real by-name paths:

```
adb shell su -c "ls -l /dev/block/platform/*/by-name/"
```
or open the `MTxxxx_Android_scatter.txt` file from your stock firmware
package (found alongside the `.img` files if you unpack the OTA/flashable
zip with SP Flash Tool's downloader or a tool like `scatter-xml` parsers).

Fill these into `recovery.fstab` and `BoardConfig.mk`. Getting this wrong
is the #1 reason TWRP builds soft-brick a first boot attempt.

## Step 3 — Kernel ✅ resolved (prebuilt path)

No GPL kernel source release exists for the G7 Tab Pro yet (checked
UMIDIGI's community forum — only SPFT firmware packages are posted, same
place you likely got your `boot.img`/`vendor_boot.img`/`dtbo.img` from).
`BoardConfig.mk` now points at real extracted binaries in `prebuilt/`:

- `Image.gz` — the actual gzip-compressed kernel exactly as embedded in your stock `boot.img` (decompresses to a valid ARM64 kernel, verified)
- `dtb.img` — the base hardware device tree, extracted from `vendor_boot.img`'s dt-table-wrapped dtb section
- `dtbo.img` — your uploaded file, used as-is
- `generic_ramdisk.lz4` / `vendor_ramdisk.lz4` — extracted for reference/TWRP-ramdisk-patching purposes, not wired into BoardConfig directly (TWRP's build tooling handles ramdisk patching itself)

Trade-off: no kernel patching (no KernelSU, no bug fixes) until/unless
UMIDIGI provides source on request via their community forum. This is
sufficient to attempt a first TWRP/OrangeFox build and a ROM build.

## Step 4 — Donor tree: FOUND and validated

`github.com/MT6789-Rock/device_xiaomi_rock` (Redmi 11 Prime 4G / POCO M5,
same MT6789 + Mali-G57 MC2 platform) independently confirmed real values
already in this tree — boot header v4, page size 4096, and the exact
kernel cmdline `bootopt=64S3,32N2,64N2` — and caught a genuine bug: kernel
offsets are now fixed to be relative to `BOARD_KERNEL_BASE` per mkbootimg's
actual convention (was previously folded incorrectly).

**Important discovery from checking the donor's approach**: your
`vendor_boot.img`'s ramdisk carries 175 real `.ko` kernel modules (clocks,
charger, `cfg80211.ko`/Wi-Fi stack, etc.) — these are now extracted into
`prebuilt/modules/` and wired into `BoardConfig.mk` via
`BOARD_VENDOR_RAMDISK_KERNEL_MODULES`. **This matters**: the prebuilt
kernel Image alone boots, but most peripheral drivers live in these
modules, not the kernel binary itself. Skipping this step would have
produced a tablet that boots but has no Wi-Fi/etc.

**One caveat about this specific donor**: it sets `TARGET_NO_RECOVERY :=
true`, meaning it's built as a ROM-only tree with recovery folded into
`vendor_boot` rather than a standalone TWRP/OrangeFox build. If a
dedicated TWRP build doesn't come together cleanly using this device
tree as scaffolding, search for a `twrp_device_...`-prefixed sibling repo
— TWRP-specific trees are often maintained separately from the ROM tree
even for the same physical device.

`TARGET_RECOVERY_FSTAB` now points at your real, uploaded
`rootdir/etc/fstab.mt6789` directly (matching the donor's convention)
instead of the earlier hand-adapted `recovery.fstab`, which is kept
in the tree for reference only.

## Building TWRP — do this on your own Linux machine

I can't run `repo sync`/compile from here (no network access, and a full
build takes hours) — but here's the exact, verified workflow:

```bash
mkdir ~/twrp && cd ~/twrp
repo init --depth=1 -u https://github.com/minimal-manifest-twrp/platform_manifest_twrp_aosp.git -b twrp-12.1
repo sync

# Drop this whole device tree in:
mkdir -p device/umidigi
cp -r /path/to/device_umidigi_g7tabpro device/umidigi/g7tabpro

export ALLOW_MISSING_DEPENDENCIES=true
. build/envsetup.sh
lunch twrp_g7tabpro-eng

# NOT "mka recoveryimage" — you have no /recovery partition, and
# BOARD_MOVE_RECOVERY_RESOURCES_TO_VENDOR_BOOT is set, so:
mka vendorbootimage
```

`twrp-12.1` is still the current minimal manifest branch and explicitly
covers Android 10+, so your Android 13 device is fine on it.

**Before this will build cleanly**, expect to hit a `vendor/twrp/config/common.mk: No such file` or similar error — check what actually landed at
`vendor/twrp/` after `repo sync` and adjust the inherit path in
`twrp_g7tabpro.mk` to match; minimal-manifest-twrp has moved this file
between branches before. This is normal first-build friction, not a sign
something upstream of it is wrong.

**Optional sanity check**: `pip install twrpdtgen && python3 -m twrpdtgen boot.img`
can auto-generate a comparison device tree from your `boot.img`. Its
documented support tops out around Android 12 and dynamic-partition (A/B
+ super) devices are explicitly a partial case for it, so don't expect a
clean success — but if it runs, diffing its output against this tree is a
useful second opinion, especially on anything under `recovery/root/`.

## Step 5 — Extract proprietary blobs

Once codename + partitions are confirmed, run `extract-files.sh` against
a `adb pull`'d or mounted copy of `/vendor` and `/system` from the stock
firmware. Do **not** hand-write `proprietary-files.txt` — let
extract-utils generate it, or you'll ship broken/missing blobs.

## What's actually in this skeleton

- `Android.mk`, `AndroidProducts.mk`, `lineage_g7tabpro.mk` — product build entry points
- `device.mk` — package/HAL inheritance, templated
- `BoardConfig.mk` — MT6789 target config with placeholder partition sizes
- `recovery.fstab` — TWRP/OrangeFox mount table, placeholder by-name paths
- `extract-files.sh`, `proprietary-files.txt` — extract-utils scaffold (empty list, by design — see Step 5)
- `vendorsetup.sh` — lunch combo helper
