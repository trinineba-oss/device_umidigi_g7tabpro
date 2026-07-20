# Device Tree — UMIDIGI G7 Tab Pro (codename: `g7tabpro`)

TWRP/OrangeFox device tree for the UMIDIGI G7 Tab Pro (MediaTek Helio G99 /
MT6789, Android 13, 8GB/256GB, 11" 1200x1920). Built from scratch — no
public device tree existed for this tablet before this one.

## Current status

- ✅ **Builds successfully** against the `twrp-12.1` minimal manifest (`lunch twrp_g7tabpro-eng && mka vendorbootimage`)
- ❌ **Does not boot** — the resulting `vendor_boot.img` produces a silent hang (no crash, no kernel panic, no `pstore` capture) when flashed. Root cause not yet identified. See [Open problem: silent boot hang](#open-problem-silent-boot-hang) below.
- ✅ A separately-built image (Hovatek's TWRP auto-builder tool, not built from this tree) **does boot** on this exact hardware, with known limitations: no working touchscreen, cannot mount/decrypt `/data`. Confirms the hardware/partition model in this tree is fundamentally correct even though our own build doesn't boot yet.

If you're picking this project up: the fastest way to make progress is
probably comparing structurally against a working image (see below) rather
than continuing to guess at `BoardConfig.mk` flags — that approach is what
found every fix below, but has hit diminishing returns without a UART/serial
console to see what a silent hang is actually doing.

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

## Open problem: silent boot hang

The built `vendor_boot.img` hangs at the UMIDIGI splash screen with **no
crash, no `pstore`/`console-ramoops` capture, and no distinguishing symptom**
beyond "stuck." This was debugged extensively without resolution:

**Ruled out** (confirmed NOT the cause, each with real evidence):
- AVB/vbmeta verification chain (`vbmeta`/`vbmeta_vendor`/`vbmeta_system` all confirmed disabled and correctly flashed)
- A stale DSU (Dynamic System Update) boot flag from an earlier `dsusideload` test — found via `pstore` analysis, confirmed cleared (`/metadata/gsi/dsu/active` removed, verified with `sync` before reboot), and the "same crash" observed afterward was proven to be `pstore` returning stale cached data, not a new panic
- Missing vendor blobs — this tree deliberately doesn't inherit vendor blob extraction for the TWRP build (matches convention in every real reference tree checked), and isn't needed for TWRP to reach its own UI
- Content differences vs. a working reference — kernel modules and `fstab.mt6789` are byte-for-byte identical between this tree's build and a working reference image

**Fixed along the way** (real bugs, but didn't resolve the hang on their own):
- Kernel offset math (was folded into `BOARD_KERNEL_BASE` incorrectly instead of kept as separate offsets)
- `TARGET_2ND_ARCH_VARIANT` needed `armv8-2a`, not `armv8-a`
- `TARGET_SUPPORTS_64_BIT_APPS` needed to be explicit (newer `board_config.mk` hard-errors instead of warning)
- `BOARD_USES_RECOVERY_AS_BOOT` conflicts with `BOARD_USES_GENERIC_KERNEL_IMAGE` — removed
- `TARGET_COPY_OUT_VENDOR`/`PRODUCT`/`VENDOR_DLKM`/`ODM_DLKM` were never explicitly set, causing a `root/vendor` symlink-vs-populated-directory rsync conflict
- `BOARD_*IMAGE_PARTITION_TYPE` should be `BOARD_*IMAGE_FILE_SYSTEM_TYPE` (naming bug)
- `first_stage_ramdisk` was completely empty — `TARGET_RECOVERY_FSTAB` in `BoardConfig.mk` only tells build tools which fstab to *reference*, doesn't copy it into the ramdisk; needed explicit `PRODUCT_COPY_FILES`
- `vendor_boot`'s ramdisk table had only 1 fragment instead of the correct 2 (platform/recovery split) — fixed via `BOARD_INCLUDE_RECOVERY_RAMDISK_IN_VENDOR_BOOT`

**Still open / worth trying next**:
- Move kernel module placement from the platform fragment to the recovery fragment (matches working reference; untested whether it matters)
- Deeper AVB footer/descriptor-level comparison against a working image — checked footer presence and basic structure (both present, similar format), but not full descriptor contents
- A genuine UART/serial console would resolve this immediately; no such access has been available for this debugging so far

### Known-working reference (not built from this tree)

Hovatek's TWRP auto-builder tool produces a `vendor_boot.img` that **does
boot** on this exact tablet, confirming the partition model, fstab, and
kernel modules in this tree are correct — it's specifically something about
how the final image gets *assembled* that differs. Known limitations of
that image: touchscreen doesn't work, `/data` cannot be mounted/decrypted.
Useful as a working baseline for touch/decryption debugging even though
it's not derived from this device tree.

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
