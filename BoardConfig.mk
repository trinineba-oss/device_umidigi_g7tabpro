# Copyright (C) 2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#
# Values below marked "CONFIRMED" were parsed directly from your uploaded
# scatter file / boot.img / vendor_boot.img / dtbo.img headers. Everything
# else is still TODO — see README.md.

DEVICE_PATH := device/umidigi/g7tabpro

# Architecture — MT6789 (Helio G99): 2x Cortex-A76 @ 2.2GHz + 6x Cortex-A55 @ 2.0GHz
TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-a
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_VARIANT := cortex-a55
TARGET_CPU_VARIANT_RUNTIME := cortex-a55
TARGET_2ND_ARCH := arm
TARGET_2ND_ARCH_VARIANT := armv8-2a
TARGET_2ND_CPU_ABI := armeabi-v7a
TARGET_2ND_CPU_ABI2 := armeabi
TARGET_2ND_CPU_VARIANT := cortex-a55
TARGET_2ND_CPU_VARIANT_RUNTIME := cortex-a55

# Required explicitly — newer board_config.mk hard-errors if neither this
# nor TARGET_SUPPORTS_32_BIT_APPS is set on a 64-bit TARGET_ARCH, instead
# of just warning like older AOSP versions did.
TARGET_SUPPORTS_64_BIT_APPS := true

TARGET_BOARD_PLATFORM := mt6789         # CONFIRMED — ro.board.platform in build.prop
TARGET_BOOTLOADER_BOARD_NAME := mt6789

# Kernel — no GPL source release exists for this device yet (checked
# UMIDIGI's community forum, only SPFT firmware packages are posted).
# Using the real stock prebuilt images extracted from your boot.img/
# vendor_boot.img/dtbo.img instead. Trade-off: no kernel patches (no
# KernelSU, no bug fixes) until/unless UMIDIGI provides source on request.
# TARGET_PREBUILT_KERNEL := $(DEVICE_PATH)/prebuilt/Image.gz
# ^ commented out: TARGET_NO_KERNEL := true below makes this inert for
# the vendorbootimage build target (vendor_boot never contains a kernel).
# Keep prebuilt/Image.gz on disk regardless — needed if a full ROM build
# is attempted later.
BOARD_DTB_SIZE := 182269
# BOARD_PREBUILT_DTBIMAGE_DIR, not TARGET_PREBUILT_DTB — the latter is the
# older single-file convention and produces no ninja rule to build dtb.img
# when BOARD_USES_GENERIC_KERNEL_IMAGE is set (confirmed by an actual
# "missing and no known rule to make it" build failure). This directory
# form is what actually generates that rule; all .dtb files inside it get
# concatenated into dtb.img.
BOARD_PREBUILT_DTBIMAGE_DIR := $(DEVICE_PATH)/prebuilt/dtb
TARGET_PREBUILT_DTBO := $(DEVICE_PATH)/prebuilt/dtbo.img
TARGET_KERNEL_ARCH := arm64
BOARD_KERNEL_IMAGE_NAME := Image.gz
# If you later obtain kernel source, delete the 3 TARGET_PREBUILT_* lines
# above and uncomment these instead:
# TARGET_KERNEL_SOURCE := kernel/umidigi/mt6789
# TARGET_KERNEL_CONFIG := g7tabpro_defconfig
# TARGET_KERNEL_HEADER_ARCH := arm64

# --- Boot image — CONFIRMED, cross-checked against a real shipped MT6789
# device tree (github.com/MT6789-Rock/device_xiaomi_rock, same SoC/GPU).
# Their BOARD_KERNEL_CMDLINE, header version, and page size independently
# matched what I parsed from your files exactly. Their offset CONVENTION
# also caught a bug in the previous version of this file — offsets are
# relative to BOARD_KERNEL_BASE, not folded into it. Fixed below.
BOARD_BOOT_HEADER_VERSION := 4
BOARD_KERNEL_CMDLINE := bootopt=64S3,32N2,64N2
BOARD_KERNEL_BASE := 0x3fff8000
BOARD_KERNEL_OFFSET := 0x00008000
BOARD_KERNEL_PAGESIZE := 4096
BOARD_RAMDISK_OFFSET := 0x26f08000
BOARD_KERNEL_TAGS_OFFSET := 0x07c88000
BOARD_DTB_OFFSET := 0x07c88000
BOARD_MKBOOTIMG_ARGS += --kernel_offset $(BOARD_KERNEL_OFFSET)
BOARD_MKBOOTIMG_ARGS += --ramdisk_offset $(BOARD_RAMDISK_OFFSET)
BOARD_MKBOOTIMG_ARGS += --tags_offset $(BOARD_KERNEL_TAGS_OFFSET)
BOARD_MKBOOTIMG_ARGS += --dtb_offset $(BOARD_DTB_OFFSET)
BOARD_MKBOOTIMG_ARGS += --header_version $(BOARD_BOOT_HEADER_VERSION)
BOARD_INCLUDE_DTB_IN_BOOTIMG := true
BOARD_RAMDISK_USE_LZ4 := true
BOARD_USES_GENERIC_KERNEL_IMAGE := true
BOARD_KERNEL_SEPARATED_DTBO := true
TARGET_NO_KERNEL_OVERRIDE := true
# TWRP/OrangeFox will very likely need this too — the donor uses it for
# the same GKI + vendor_boot + no-recovery-partition layout your device has:
BOARD_MOVE_RECOVERY_RESOURCES_TO_VENDOR_BOOT := true

# --- Kernel modules — CONFIRMED. Your vendor_boot.img's ramdisk contains
# 175 real .ko driver modules (clocks, charger, cfg80211/Wi-Fi, etc.) plus
# modules.load / modules.load.recovery / modules.dep. These are extracted
# into prebuilt/modules/ already. Without wiring these in, the prebuilt
# kernel alone boots but most peripherals silently don't work.
# All .ko files are placed into the vendor ramdisk once (the physical set
# of module files). The two *_LOAD lists then control *when* each loads:
#   _KERNEL_MODULES_LOAD          -> loaded in first-stage init on NORMAL boot
#   _RECOVERY_KERNEL_MODULES_LOAD -> loaded only when recovery/fastbootd is
#                                    selected from the ramdisk
#
# IMPORTANT (this was a real bug in earlier revisions): the previous version
# merged modules.load.recovery INTO the main first-stage list, which
# force-loaded all ~175 modules — including 19 recovery-only ones — during
# first-stage init on every normal boot. AOSP explicitly warns against this
# ("don't load the recovery mode modules in first stage init during normal
# boot flow"), and it's a plausible cause of a silent early-boot hang. The
# two load lists are kept strictly separate below.
#
# The full physical module set = union of both load lists (so every .ko that
# either list references actually exists in the ramdisk), but the LOAD lists
# themselves stay distinct.
BOARD_VENDOR_RAMDISK_KERNEL_MODULES_LOAD := $(strip $(shell cat $(DEVICE_PATH)/prebuilt/modules/modules.load))
BOARD_VENDOR_RAMDISK_RECOVERY_KERNEL_MODULES_LOAD := $(strip $(shell cat $(DEVICE_PATH)/prebuilt/modules/modules.load.recovery))
BOARD_VENDOR_RAMDISK_KERNEL_MODULES := $(addprefix $(DEVICE_PATH)/prebuilt/modules/, \
    $(sort $(BOARD_VENDOR_RAMDISK_KERNEL_MODULES_LOAD) $(BOARD_VENDOR_RAMDISK_RECOVERY_KERNEL_MODULES_LOAD)))
# TWRP needs to be explicitly told to actually load these at runtime —
# building them into the ramdisk alone isn't enough.
TW_LOAD_VENDOR_BOOT_MODULES := true

# --- Everything below CONFIRMED against a real, production, currently-
# working common tree for this exact SoC:
# github.com/transsion-mt6789-recovery/twrp-device_transsion_mt6789-common
# (used by TECNO POVA 6 / LI7, same MT6789 + Mali-G57 MC2, confirmed
# working Display+Decryption on OrangeFox). These are gaps our tree had
# that theirs doesn't — folding them in wholesale rather than guessing
# which one specifically matters.

# vendor_boot never contains a kernel at all (confirmed by AOSP's own
# boot image format spec) — TARGET_PREBUILT_KERNEL above was likely
# inert for the vendorbootimage build target specifically.
TARGET_NO_KERNEL := true
TARGET_NO_RECOVERY := true

# Recovery ramdisk fragment tagging inside vendor_boot's ramdisk table —
# we never had this. Without it the recovery content may not be marked
# as something the bootloader should load when booting into recovery.
BOARD_INCLUDE_RECOVERY_RAMDISK_IN_VENDOR_BOOT := true

BOARD_AVB_ENABLE := true
BOARD_USES_METADATA_PARTITION := true

# vendor_boot's own cmdline field, separate from BOARD_KERNEL_CMDLINE
BOARD_VENDOR_BASE := 0x3fff8000
BOARD_VENDOR_CMDLINE := bootopt=64S3,32N2,64N2
BOARD_MKBOOTIMG_ARGS += --vendor_cmdline $(BOARD_VENDOR_CMDLINE)
BOARD_MKBOOTIMG_ARGS += --board ""

# Anti-rollback hack: pins security patch/version to absurdly future
# values so TEE/RPMB-level downgrade rejection doesn't kick in — this
# operates independently of the vbmeta verification flags, so disabling
# vbmeta verification alone doesn't cover it.
PLATFORM_SECURITY_PATCH := 2099-12-31
PLATFORM_VERSION := 99.87.36
PLATFORM_VERSION_LAST_STABLE := $(PLATFORM_VERSION)
VENDOR_SECURITY_PATCH := $(PLATFORM_SECURITY_PATCH)
BOOT_SECURITY_PATCH := $(PLATFORM_SECURITY_PATCH)

# Build compatibility hacks
BUILD_BROKEN_DUP_RULES := true
BUILD_BROKEN_ELF_PREBUILT_PRODUCT_COPY_FILES := true
ALLOW_MISSING_DEPENDENCIES := true

# TWRP hardening flags proven for this platform
BOARD_HAS_LARGE_FILESYSTEM := true
BOARD_HAS_NO_SELECT_BUTTON := true
BOARD_SUPPRESS_SECURE_ERASE := true
TW_USE_FSCRYPT_POLICY := 2
TW_FORCE_KEYMASTER_VER := true

# Explicit partition-to-group mapping for dynamic partitions — we had
# the group size but never declared which partitions belong to it.
BOARD_MAIN_GROUP_PARTITION_LIST += \
    odm_dlkm \
    product \
    system \
    vendor \
    vendor_dlkm

# --- Partitions — CONFIRMED sizes from scatter file (identical in both the
# EMMC and UFS sections of the scatter file, so accurate regardless of which
# storage bus your specific unit actually has) ---
BOARD_BOOTIMAGE_PARTITION_SIZE := 67108864          # boot_a, 0x4000000
BOARD_VENDOR_BOOTIMAGE_PARTITION_SIZE := 67108864   # vendor_boot_a, 0x4000000 — matches your vendor_boot.img file size exactly
BOARD_INIT_BOOT_IMAGE_PARTITION_SIZE := 8388608     # init_boot_a, 0x800000
BOARD_DTBOIMG_PARTITION_SIZE := 8388608             # dtbo_a, 0x800000 — matches your dtbo.img file size exactly

# Dynamic partitions — CONFIRMED: this device uses a "super" partition
# (system/vendor/product live as logical partitions inside it, not as
# separate physical partitions)
BOARD_SUPER_PARTITION_SIZE := 9663676416            # super, 0x240000000
BOARD_SUPER_PARTITION_GROUPS := main_group
BOARD_MAIN_GROUP_SIZE := 9647161344                 # super size minus ~16MB metadata headroom — TODO verify against OEM fstab if you find one
TARGET_USES_DYNAMIC_PARTITIONS := true
BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_PRODUCTIMAGE_FILE_SYSTEM_TYPE := ext4
# Anticipating the same board_config.mk check that just caught
# BOARD_PRODUCTIMAGE_FILE_SYSTEM_TYPE — vendor_dlkm/odm_dlkm were set as
# separate TARGET_COPY_OUT_* in the same batch, so they likely need this too.
BOARD_VENDOR_DLKMIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_ODM_DLKMIMAGE_FILE_SYSTEM_TYPE := ext4

# CONFIRMED fix for a real build failure: "could not make way for new
# symlink: root/vendor / cannot delete non-empty directory: root/vendor".
# Without these set explicitly, the build system defaults to treating
# /vendor (and friends) as merged under /system, which means it expects
# root/vendor to be a SYMLINK. But this device has vendor/product/
# vendor_dlkm/odm_dlkm as genuinely separate partitions (confirmed
# repeatedly via the scatter file and real fstab.mt6789), and
# BOARD_VENDOR_RAMDISK_KERNEL_MODULES installs real files into a real
# vendor/ directory tree — hence the symlink-vs-populated-directory clash.
TARGET_COPY_OUT_VENDOR := vendor
TARGET_COPY_OUT_PRODUCT := product
TARGET_COPY_OUT_VENDOR_DLKM := vendor_dlkm
TARGET_COPY_OUT_ODM_DLKM := odm_dlkm

BOARD_USERDATAIMAGE_FILE_SYSTEM_TYPE := f2fs
BOARD_FLASH_BLOCK_SIZE := 131072

# A/B — CONFIRMED: every partition in the scatter file has _a/_b variants
AB_OTA_UPDATER := true
AB_OTA_PARTITIONS := \
    boot \
    dtbo \
    vendor_boot \
    vbmeta \
    vbmeta_system \
    vbmeta_vendor \
    system \
    vendor \
    product \
    vendor_dlkm \
    odm_dlkm \
    dpm \
    gz \
    lk \
    mcupm \
    md1img \
    pi_img \
    scp \
    spmfw \
    sspm \
    tee
# MTK firmware partitions (dpm/gz/lk/mcupm/md1img/pi_img/scp/spmfw/sspm/tee)
# confirmed present as _a/_b pairs in your scatter file. Matches the
# pattern used by github.com/MT6789-Rock/device_xiaomi_rock for the same
# platform. init_boot intentionally excluded — see note below.
# NOTE: init_boot removed from this list — the real fstab.mt6789 has no
# init_boot mount entry, even though the partition is reserved in the
# scatter file. boot.img still carries a full ramdisk itself (confirmed
# by parsing your boot.img header). Add it back only if testing shows
# this device actually expects a separate init_boot flash/update.

# Recovery — CONFIRMED: no physical /recovery partition exists in the
# scatter file. This device boots recovery mode via a ramdisk toggle in
# boot.img (classic "recovery as boot"), not a dedicated recovery image.
# NOTE: BOARD_USES_RECOVERY_AS_BOOT removed — it's mutually exclusive
# with BOARD_USES_GENERIC_KERNEL_IMAGE (set above, near the kernel
# section). BOARD_MOVE_RECOVERY_RESOURCES_TO_VENDOR_BOOT is the correct
# mechanism for a GKI device like this one; the build system errors if
# both are set.
TARGET_RECOVERY_FSTAB := $(DEVICE_PATH)/rootdir/etc/fstab.mt6789
TW_THEME := portrait_hdpi
TW_INCLUDE_CRYPTO := true
TW_INCLUDE_FBE := true
TW_INCLUDE_FBE_METADATA_DECRYPT := true
TW_EXCLUDE_APEX := true
BOARD_HAS_NO_REAL_SDCARD := false
TARGET_USES_MKE2FS := true
TW_NEW_ION_HEAP := true
TW_NO_HAPTICS := true

# Pulls in resetprop and libresetprop. The recovery binary in this manifest
# links against libresetprop.so, and without this the binary fails at the
# dynamic linker with "library libresetprop.so not found" and exits status 1
# on every init restart — the actual cause of the apparent boot hang.
TW_INCLUDE_RESETPROP := true
RECOVERY_SDCARD_ON_DATA := true

# Graphics
BOARD_GPU_DRIVERS := mali
TARGET_USES_MALI_GPU := true

# Screen — 11" 1200x1920 IPS
TW_SCREEN_WIDTH := 1200
TW_SCREEN_HEIGHT := 1920

-include vendor/umidigi/g7tabpro/BoardConfigVendor.mk
