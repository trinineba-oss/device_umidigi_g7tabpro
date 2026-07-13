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

# Required explicitly on newer board_config.mk
TARGET_SUPPORTS_64_BIT_APPS := true

TARGET_BOARD_PLATFORM := mt6789         # CONFIRMED — ro.board.platform in build.prop
TARGET_BOOTLOADER_BOARD_NAME := mt6789

# Kernel — no GPL source release exists for this device yet (checked
# UMIDIGI's community forum, only SPFT firmware packages are posted).
# Using the real stock prebuilt images extracted from your boot.img/
# vendor_boot.img/dtbo.img instead. Trade-off: no kernel patches (no
# KernelSU, no bug fixes) until/unless UMIDIGI provides source on request.
TARGET_PREBUILT_KERNEL := $(DEVICE_PATH)/prebuilt/Image.gz
TARGET_PREBUILT_DTB := $(DEVICE_PATH)/prebuilt/dtb.img
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
BOARD_VENDOR_RAMDISK_KERNEL_MODULES_LOAD := $(strip $(shell cat $(DEVICE_PATH)/prebuilt/modules/modules.load))
BOARD_VENDOR_RAMDISK_KERNEL_MODULES := $(addprefix $(DEVICE_PATH)/prebuilt/modules/, $(BOARD_VENDOR_RAMDISK_KERNEL_MODULES_LOAD))
BOARD_VENDOR_RAMDISK_RECOVERY_KERNEL_MODULES_LOAD := $(strip $(shell cat $(DEVICE_PATH)/prebuilt/modules/modules.load.recovery))
RECOVERY_MODULES := $(addprefix $(DEVICE_PATH)/prebuilt/modules/, $(BOARD_VENDOR_RAMDISK_RECOVERY_KERNEL_MODULES_LOAD))
BOARD_VENDOR_RAMDISK_KERNEL_MODULES := $(sort $(BOARD_VENDOR_RAMDISK_KERNEL_MODULES) $(RECOVERY_MODULES))

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
BOARD_SYSTEMIMAGE_PARTITION_TYPE := ext4
BOARD_VENDORIMAGE_PARTITION_TYPE := ext4
BOARD_PRODUCTIMAGE_PARTITION_TYPE := ext4

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
# Using the real init fstab directly, matching the donor tree's convention
# (device_xiaomi_rock does the same) rather than a hand-adapted TWRP-style
# recovery.fstab — this is your actual uploaded fstab.mt6789, verbatim.
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
RECOVERY_SDCARD_ON_DATA := true

# Graphics
BOARD_GPU_DRIVERS := mali
TARGET_USES_MALI_GPU := true

# Screen — 11" 1200x1920 IPS
TW_SCREEN_WIDTH := 1200
TW_SCREEN_HEIGHT := 1920

-include vendor/umidigi/g7tabpro/BoardConfigVendor.mk
