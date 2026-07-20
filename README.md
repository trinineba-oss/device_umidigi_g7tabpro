# Device Tree Skeleton — UMIDIGI G7 Tab Pro (placeholder codename: g7tabpro)

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

## Step 3 — Get the kernel

MT6789 kernel is Linux 4.19 on most Android 13 Helio G99 devices — verify
by extracting `boot.img` with `unpackbootimg` and checking the kernel
version string (`strings kernel | grep "Linux version"`).

UMIDIGI has released GPL kernel source for other devices before (A5 Pro,
BISON, F2) on request via their community forum
(community.umidigi.com) or GitHub (github.com/UMIDIGI-Official). No G7 Tab
Pro release exists yet — you'll likely need to request it citing GPLv2
obligations. If they don't respond, many MTK unofficial builds ship with
the **prebuilt stock kernel** (`Image` + `dtbo.img` extracted from stock
`boot.img`) instead of building from source — this works for both
TWRP and a ROM, it just means no kernel patches/KernelSU/etc.

## Step 4 — Find a donor tree

Don't build BoardConfig.mk flags from nothing — fork the structure of an
existing MT6789/Helio G99 device tree and adjust partition sizes/paths.
Good search terms on GitHub: `mt6789 device tree lineageos`,
`helio g99 twrp device tree`. Similar Infinix/Redmi/POCO MT6789 phones
already have working trees; their non-display, non-partition config
(HALs, power, media codecs) usually needs zero changes.

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
