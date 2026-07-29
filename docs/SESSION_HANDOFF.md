# UMIDIGI G7 Tab Pro — TWRP Bring-Up: Session Handoff

**Repo:** https://github.com/trinineba-oss/device_umidigi_g7tabpro
**Device:** UMIDIGI G7 Tab Pro — MediaTek Helio G99 (MT6789), Android 13,
8GB/256GB, 11" 1200x1920 IPS
**Goal:** Working custom recovery, primarily to flash ROMs on-device without a PC.
**Status:** TWRP builds and boots to its main menu. **No input works** — neither
touch nor hardware buttons. Root cause not yet identified.

---

## 1. Current state (read this first)

TWRP 3.7.1_12 builds cleanly against the `twrp-12.1` minimal manifest, flashes
to `vendor_boot`, boots, renders graphics correctly, mounts all partitions, and
displays the main menu.

**The blocker:** neither the touchscreen NOR the volume/power buttons navigate
the menu. This was discovered late. Prior to that discovery, ~3 build cycles
were spent on the touch driver / device tree specifically — that work is
correct and committed, but it is probably NOT the actual problem, because
buttons use `mtk-pmic-keys` / `mtk-kpd`, which are confirmed present and
working as input devices (`getevent -p` lists them).

**Working hypothesis:** TWRP's UI is rendering but not processing input events
— either the input thread is wedged, or the process is hung after drawing the
menu and the screen is a static frame.

**adb is unavailable at the menu.** It works during early boot (device appears
as `recovery` in `adb devices`) but drops once TWRP reaches the menu. Attempts
to restore it (kill-server/start-server, replug, different ports) all failed.
An OTG mouse got power but no cursor — inconclusive, since the USB port can be
in peripheral OR host mode, not both.

**A diagnostic mechanism is already built and shipped** (see §6). It writes
logs to a microSD card, no adb needed. As of the end of this session it had
been built and verified present in the image but the logs had not yet been
collected. **Collecting them is the single highest-value next step.**

---

## 2. Confirmed hardware facts (all reverse-engineered, not guessed)

| Item | Value |
|---|---|
| SoC | MT6789 (Helio G99), Mali-G57 MC2 |
| Kernel | 5.10.185-android12-9-00001-gc475c0851364-ab10989012 |
| Codename | `ro.product.vendor.device=G7_Tab_Pro` (build codename `g7tabpro`) |
| Partition scheme | A/B, dynamic partitions, `super` ~9GiB |
| Recovery partition | **None** — boot-as-recovery via `vendor_boot` |
| `/cache` | Does not exist |
| Boot header | v4 (GKI-style) |
| Kernel cmdline | `bootopt=64S3,32N2,64N2` |
| `BOARD_KERNEL_BASE` | `0x3fff8000` |
| kernel/ramdisk/tags/dtb offsets | `0x8000` / `0x26f08000` / `0x07c88000` / `0x07c88000` |
| Panel | `l0a9w006c_dsi_vdo` (named in `/proc/cmdline`) |
| Touch controller | **Chipone TDDI** — driver `tddi_9551.ko` |
| Security patch (stock) | 2024-10-05 |

`vendor_boot`'s ramdisk table has **2 fragments**: a small `platform` type
(first-stage tools) and a larger `recovery` type (TWRP + modules + everything
else). This is required; a single-fragment image does not work.

---

## 3. Bugs found and fixed (chronological)

Each of these was a real bug. Only #8 was the thing actually preventing TWRP
from running at all.

1. **Kernel offsets** were folded into `BOARD_KERNEL_BASE` instead of kept as
   separate offsets. Caught by comparing against `MT6789-Rock/device_xiaomi_rock`.
2. **`TARGET_2ND_ARCH_VARIANT`** needed `armv8-2a`, not `armv8-a`.
3. **`TARGET_SUPPORTS_64_BIT_APPS`** must be set explicitly — newer
   `board_config.mk` hard-errors instead of warning.
4. **`BOARD_USES_RECOVERY_AS_BOOT`** conflicts with
   `BOARD_USES_GENERIC_KERNEL_IMAGE`; removed in favour of
   `BOARD_MOVE_RECOVERY_RESOURCES_TO_VENDOR_BOOT`.
5. **`TARGET_COPY_OUT_VENDOR`/`PRODUCT`/`VENDOR_DLKM`/`ODM_DLKM`** were never
   set, causing a `root/vendor` symlink-vs-populated-directory rsync failure.
6. **`BOARD_*IMAGE_PARTITION_TYPE`** is the wrong variable name; it must be
   `BOARD_*IMAGE_FILE_SYSTEM_TYPE`.
7. **`first_stage_ramdisk` was empty.** `TARGET_RECOVERY_FSTAB` only tells the
   build which fstab to *reference*; it does not copy it into the ramdisk.
   Needed explicit `PRODUCT_COPY_FILES`.
8. **Missing `libresetprop.so`** ← *the actual boot blocker*. The `recovery`
   binary failed at the dynamic linker and exited status 1; init restarted it
   every 5 seconds forever, which looked exactly like a hang. Diagnosed with
   `adb shell "/system/bin/recovery 2>&1"`. Fixed via
   `TW_INCLUDE_RESETPROP := true` plus packaging the library.
9. **Wrong pixel format.** TWRP fell back to RGB565 and segfaulted
   (`SIGSEGV`/`SEGV_ACCERR`) on the first splash draw. Fixed with
   `TARGET_RECOVERY_PIXEL_FORMAT := "RGBX_8888"`.
10. **FBE decryption hang.** TWRP reached splash then hung forever trying to
    decrypt `/data`; the keymaster version was unresolvable because `/vendor`
    isn't mounted that early. Fixed by stripping `fileencryption=` and
    `keydirectory=` from the `/data` entry.
11. **Kernel module load lists were merged.** `modules.load` and
    `modules.load.recovery` were combined, force-loading 19 recovery-only
    modules during normal-boot first-stage init (AOSP explicitly warns against
    this). Now kept separate.
12. **Single ramdisk fragment.** Fixed with
    `BOARD_INCLUDE_RECOVERY_RAMDISK_IN_VENDOR_BOOT := true`.
13. **Malformed `twrp.flags`** — two mount entries welded onto one line.

---

## 4. Ruled out (do not re-investigate)

- **AVB / vbmeta verification.** Hangs occurred identically with verification
  enabled AND disabled on all three (`vbmeta`, `vbmeta_vendor`,
  `vbmeta_system`). Not the cause.
- **Stale DSU flag.** An abandoned `dsusideload` attempt left
  `/metadata/gsi/dsu/active`, causing a real kernel panic
  (`Attempted to kill init`, invalid ext4 superblock on `system_gsi`). Cleared
  via `rm /metadata/gsi/dsu/active` + `sync`. Genuinely fixed; not the current
  problem.
- **Missing vendor blobs.** The TWRP product makefile deliberately does not
  inherit `device.mk`. This matches every reference tree checked and is not
  required for TWRP to reach its UI.
- **Kernel module vermagic mismatch.** `tddi_9551.ko` has the *same* vermagic
  as the 171 modules that load successfully. Not a blocker.
- **`mtk_plpath_utils` missing.** `/dev/block/by-name/` is fully populated
  without it. Harmless; the exec is now commented out.
- **`pstore` / `console-ramoops`.** Repeatedly returns *stale* data —
  `rm` on the pstore file does not always clear the underlying RAM region, so
  the same panic reappears across boots. **Verify by MD5 before trusting any
  pstore capture.** This wasted several cycles.

---

## 5. Device-tree touch work (done, unverified)

The touch driver `tddi_9551.ko` (Chipone TDDI, pulled from stock
`/vendor/lib/modules/`) binds to DT compatible `chipone_tddi`. That node exists
**only in the DTBO overlay** (`/fragment@21/__overlay__/chipone_touch@48`),
and the bootloader does **not** apply the DTBO in recovery mode (confirmed:
flashing the stock `dtbo` partition changed nothing).

Two patches were therefore merged directly into the base DTB inside
`vendor_boot` (`prebuilt/dtb/g7tabpro.dtb`, source kept at
`prebuilt/dtb/g7tabpro.dts`):

1. The `chipone_touch@48` node added under `/soc/i2c@11e00000` (`i2c0`),
   with `compatible = "chipone_tddi"`, `reg = <0x48>`, irq-gpio `0x09`,
   rst-gpio `0x98`, x-res `0x4b0` (1200), y-res `0x780` (1920).
2. The seven `ctp_*` pinctrl states added under `/soc/pinctrl`, plus
   `pinctrl-names`/`pinctrl-0..6` on the i2c bus node (these mux the physical
   SCL/SDA pins — without them the controller may be electrically unreachable).

Both verified present in the shipped DTB. **Neither made touch work.** Given
buttons don't work either, this is likely necessary-but-not-sufficient, or
aimed at the wrong layer entirely.

Useful phandles in the base DTB: `pio` = `0x49`, `chosen` = `0x50`.
Note the DTBO's own phandles (`0x4e`–`0x54`) collide with base values — use
labels and let `dtc` reassign, don't copy raw numbers.

---

## 6. The diagnostic mechanism (USE THIS FIRST)

Because adb dies at the menu, a log-dump service was added and is **already
in the current build**:

- `recovery/root/system/bin/dumplogs.sh`
- service `dumplogs` in `recovery/root/init.recovery.mt6789.rc`, started
  `on boot`

**How to use:** insert a FAT32 microSD, flash, boot to recovery, wait 60+
seconds untouched, power off, read `twrplogs/` from the card.

It captures, twice (35s and 55s after boot) so you can tell frozen from
progressing:
`dmesg.txt`, `recovery.log`, `ps.txt`, `lsmod.txt`, `getevent.txt`,
`input_devices.txt`, `dev_input.txt`, `i2c0_node.txt`, `interrupts.txt`,
`getprop.txt`, plus `dmesg_later.txt` / `recovery_later.log` / `ps_later.txt`.

**Interpretation guide:**
- `recovery.log` == `recovery_later.log` → TWRP is frozen. Look at the last
  line to see where.
- They differ → TWRP is alive; the problem is input handling specifically.
- `input_devices.txt` → does a touchscreen register at all? Do the key devices?
- `dmesg.txt` → grep `chipone`, `tddi`, `i2c`, `input`.

If the card mounts nothing (script silently produces no files), the device node
guess is wrong — the script tries `/dev/block/mmcblk1p1`, `mmcblk1`,
`mmcblk0p1`.

---

## 7. Build and flash

```bash
repo init --depth=1 -u https://github.com/minimal-manifest-twrp/platform_manifest_twrp_aosp.git -b twrp-12.1
repo sync
mkdir -p device/umidigi && cp -r device_umidigi_g7tabpro device/umidigi/g7tabpro
export ALLOW_MISSING_DEPENDENCIES=true
. build/envsetup.sh
lunch twrp_g7tabpro-eng
mka vendorbootimage     # NOT recoveryimage — no recovery partition exists
```

A GitHub Actions workflow (`.github/workflows/build-twrp.yml`) does this
automatically on push. Artifact contains `vendor_boot.img`, `dtb.img`,
`ramdisk.img` — **only `vendor_boot.img` is flashed.**

```
fastboot flash vendor_boot vendor_boot.img
fastboot reboot recovery
```

Verification is currently disabled on all three vbmeta partitions; that state
persists and does not need redoing unless stock vbmeta is reflashed.

**Always verify a fix actually landed in the image before flashing.** Editing
the wrong file has happened twice:
- `TARGET_RECOVERY_FSTAB` points at `rootdir/etc/fstab.mt6789`; the build
  copies that over `recovery/root/system/etc/recovery.fstab`, discarding edits
  made to the latter.
- `TW_SKIP_DECRYPTION_ON_BOOT` is silently ignored by this TWRP branch.
- One build was byte-identical to its predecessor because a commit never
  reached CI. Compare MD5s.

---

## 8. Recommended next steps, in order

1. **Collect the SD-card logs.** Everything below is guesswork until this is
   done.
2. From `recovery.log`, determine frozen vs. alive. If frozen, the last log
   line names the culprit.
3. If alive but input-dead: investigate TWRP's input thread —
   `input_devices.txt` and `getevent.txt` will show whether the kernel is
   delivering events at all. If the kernel sees key presses but TWRP doesn't
   react, the problem is in TWRP's event handling, not the device tree.
4. Restore adb if possible — try a USB 2.0 port (MTK recovery adb is unreliable
   on xHCI), or reassign the Windows driver to "Android ADB Interface" in
   Device Manager. A live shell is worth many build cycles.
5. Only after the above: revisit touch. Remaining untested candidates include
   the `mediatek,mt6983-i2c` compatible on an MT6789 board, I2C bus numbering,
   and a possible missing regulator/supply for the panel-integrated touch.

---

## 9. Reference trees used

- `MT6789-Rock/device_xiaomi_rock` — same SoC/GPU. Confirmed boot header v4,
  page size, cmdline; caught the kernel offset bug.
- `transsion-mt6789-recovery/twrp-device_tecno_TECNO-LI7` and its shared
  `twrp-device_transsion_mt6789-common` — same platform, **confirmed working
  Display + Decryption on OrangeFox**. Source of most `BoardConfig.mk` flags.
  Worth mining further.
- Hovatek's TWRP auto-builder produced an image that boots on this exact
  tablet (also without touch, also without `/data`). Its files were used to
  source `first_stage_ramdisk` binaries and the MT6789 init scripts.

## 10. Notes on the device tree contents

- No GPL kernel source exists for this device. `prebuilt/` holds the stock
  kernel (`Image.gz`), DTB, DTBO, 175 `.ko` modules + load lists, and
  first-stage binaries extracted from a working reference image.
- `/data` is deliberately not decryptable (crypto flags removed). Acceptable
  for ROM flashing, which writes to system/super.
- `BOARD_DTB_SIZE` in `BoardConfig.mk` is stale (182269 vs actual 183342) but
  appears to be informational only — it has not broken any build.
