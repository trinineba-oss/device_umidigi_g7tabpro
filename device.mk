# Copyright (C) 2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

LOCAL_PATH := device/umidigi/g7tabpro

# Inherit the proprietary blobs (populated by extract-files.sh, see Step 5 in README)
#
# g7tabpro-vendor.mk (not device-vendor.mk) is the correct file to inherit:
# it's the file setup-makefiles.sh actually generates (standard extract-utils
# naming), freshly regenerated 2026-08-13 from a real 2461-entry
# proprietary-files.txt with 2375 blobs verified extracted with zero errors.
# device-vendor.mk was a stale, partially-broken leftover from an earlier
# session ("1997 files" header, predates the real extraction): it put APKs
# under PRODUCT_COPY_FILES instead of PRODUCT_PACKAGES (hard build error -
# AOSP requires a proper prebuilt module for .apk), referenced a
# g7tabpro_vendor_symlinks module with no definition anywhere in the tree,
# and pointed DEVICE_MANIFEST_FILE at a manifest_compat.xml that doesn't
# exist anywhere in the actual firmware. Kept on disk as
# device-vendor.mk.superseded-backup for reference.
$(call inherit-product, vendor/umidigi/g7tabpro/g7tabpro-vendor.mk)

PRODUCT_CHARACTERISTICS := tablet

# Treble (2026-08-14): PRODUCT_SHIPPING_API_LEVEL was never set, which is the
# root of the "This device does not have Treble enabled. This is unsafe."
# warning every build emitted. build/make/core/config.mk derives
# PRODUCT_FULL_TREBLE from it (true iff >= 26); with it empty the whole chain
# collapsed to false:
#     PRODUCT_FULL_TREBLE              = false
#     PRODUCT_TREBLE_LINKER_NAMESPACES = false   <-- the load-bearing one
#     PRODUCT_ENFORCE_VINTF_MANIFEST   = false
#
# This device is unambiguously Treble (separate vendor partition, VNDK-based
# vendor blobs, VINTF manifest target-level="6"). Its vendor binaries expect
# Treble linker-namespace isolation to resolve libs from /vendor/lib64 and the
# VNDK; with namespaces disabled linkerconfig emits a legacy non-Treble config
# and vendor processes can fail to resolve their libraries entirely - a
# plausible cause of the silent ~2s reboot loop seen on the first flash test
# (init starts, vendor services fail to link, loop; no boot animation, no adb).
#
# 33 = Android 13, the API level this device originally shipped with (stock
# fingerprint UMIDIGI/G7_Tab_Pro/G7_Tab_Pro:13/TP1A.220624.014/...).
#
# NOTE: this also flips PRODUCT_ENFORCE_VINTF_MANIFEST to true, so checkvintf
# now actually enforces the VINTF manifest rather than just warning. That is
# the correct behaviour and real validation, but expect it to surface manifest
# errors that were previously passing silently.
PRODUCT_SHIPPING_API_LEVEL := 33

# Dynamic partitions (BoardConfig.mk sets TARGET_USES_DYNAMIC_PARTITIONS,
# the super-partition mechanism itself) also need
# PRODUCT_USE_DYNAMIC_PARTITIONS set here, in the product-config phase -
# it auto-derives PRODUCT_USE_DYNAMIC_PARTITION_SIZE (letting
# build_image.py compute each logical partition's size from actual
# content instead of requiring a fixed BOARD_*IMAGE_PARTITION_SIZE per
# partition). That derived var is .KATI_READONLY by the time BoardConfig.mk
# runs, so it can't be set there directly - without this, vendor.img,
# product.img, vendor_dlkm.img and odm_dlkm.img all fail at the very last
# build stage with KeyError: 'partition_size' (confirmed: hit this after a
# clean 1h49m build, 128593/129547 targets done).
PRODUCT_USE_DYNAMIC_PARTITIONS := true

# Architecture
PRODUCT_PROPERTY_OVERRIDES += \
    ro.zygote=zygote64

# Kernel/dtbo/boot
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/rootdir/etc/fstab.mt6789:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.mt6789 \
    $(LOCAL_PATH)/rootdir/etc/fstab.mt6789:$(TARGET_COPY_OUT_RAMDISK)/first_stage_ramdisk/fstab.mt6789

# Display / graphics — MT6789 uses Mali-G57 MC2, verify HAL package names
# against the donor tree you forked (Step 4 in README) once you pick one.
PRODUCT_PACKAGES += \
    android.hardware.graphics.allocator@4.0-service \
    android.hardware.graphics.composer@2.4-service

# BOARD_GPU_DRIVERS := mali (BoardConfig.mk) pulls in external/mesa3d/Android.mk,
# which hard-errors unless its own path is declared here (see the ifeq check at
# the top of that file). Real GPU rendering still comes from the proprietary
# Mali blobs extracted into vendor/ - mesa3d itself is not used at runtime.
PRODUCT_SOONG_NAMESPACES += \
    external/mesa3d

# Wi-Fi / Bluetooth — MT6789 typically pairs with an MTK combo chip.
# TODO: confirm exact chip from vendor/etc/wifi firmware filenames once
# extract-files.sh is run against the stock vendor partition.
PRODUCT_PACKAGES += \
    hostapd \
    wpa_supplicant \
    wpa_supplicant.conf

# Audio
PRODUCT_PACKAGES += \
    audio.primary.mt6789

# Recovery-related (used when this tree is also inherited by TWRP/OrangeFox builds)
PRODUCT_PACKAGES += \
    recovery-refresh \
    recovery-persist


# ---------------------------------------------------------------------------
# VNDK 31 libraries required by the Android-12-era vendor blobs (2026-08-15)
#
# This device's vendor is VNDK-31 era: stock declares ro.vndk.version=31 and
# ro.board.first_api_level=31, and its binaries link against libraries using
# the Android-11/12 "-ndk_platform.so" naming (renamed to "-ndk.so" in 13+).
#
# VNDK is REMOVED in this AOSP generation - build/make/core/config.mk
# unconditionally clears BOARD_VNDK_VERSION - so the build ships no VNDK
# snapshot and creates no VNDK linker namespace. That left 13 libraries that
# vendor binaries need resolvable from NOWHERE (verified: absent from both
# our system.img and vendor.img). A service whose shared libraries cannot be
# resolved never starts; keystore2 then finds KeyMint declared in VINTF but
# never registered, which is exactly KEYMINT_NOT_CONFIGURED (-64) - the error
# this project long attributed solely to TEE damage.
#
# With VNDK disabled, vendor processes resolve from /vendor/lib64, so the
# libraries are installed there directly rather than via a VNDK namespace.
# (PeterGSI, which boots on this hardware, instead ships
# com.android.vndk.v31.apex in system_ext - a different route to the same end.)
#
# The set is not guesswork: it is the exact unresolved closure, computed by
# walking DT_NEEDED across every ELF in /vendor/bin and /vendor/lib* and
# subtracting everything present in our system and vendor images (recursively,
# including egl/ hw/ soundfx/ subdirs). Adding these 13 resolves their own
# dependencies too - the transitive closure came back empty.
# ---------------------------------------------------------------------------
# --- VNDK 31 libraries required by the Android-12-era vendor blobs ---
PRODUCT_COPY_FILES += \
    prebuilts/vndk/v31/arm64/arch-arm64-armv8-a/shared/vndk-core/android.hardware.gnss-V1-ndk_platform.so:$(TARGET_COPY_OUT_VENDOR)/lib64/android.hardware.gnss-V1-ndk_platform.so \
    prebuilts/vndk/v31/arm64/arch-arm64-armv8-a/shared/vndk-core/android.hardware.light-V1-ndk_platform.so:$(TARGET_COPY_OUT_VENDOR)/lib64/android.hardware.light-V1-ndk_platform.so \
    prebuilts/vndk/v31/arm64/arch-arm64-armv8-a/shared/vndk-core/android.hardware.memtrack-V1-ndk_platform.so:$(TARGET_COPY_OUT_VENDOR)/lib64/android.hardware.memtrack-V1-ndk_platform.so \
    prebuilts/vndk/v31/arm64/arch-arm64-armv8-a/shared/vndk-core/android.hardware.power-V2-ndk_platform.so:$(TARGET_COPY_OUT_VENDOR)/lib64/android.hardware.power-V2-ndk_platform.so \
    prebuilts/vndk/v31/arm64/arch-arm64-armv8-a/shared/vndk-core/android.hardware.security.keymint-V1-ndk_platform.so:$(TARGET_COPY_OUT_VENDOR)/lib64/android.hardware.security.keymint-V1-ndk_platform.so \
    prebuilts/vndk/v31/arm64/arch-arm64-armv8-a/shared/vndk-core/android.hardware.security.secureclock-V1-ndk_platform.so:$(TARGET_COPY_OUT_VENDOR)/lib64/android.hardware.security.secureclock-V1-ndk_platform.so \
    prebuilts/vndk/v31/arm64/arch-arm64-armv8-a/shared/vndk-core/android.hardware.security.sharedsecret-V1-ndk_platform.so:$(TARGET_COPY_OUT_VENDOR)/lib64/android.hardware.security.sharedsecret-V1-ndk_platform.so \
    prebuilts/vndk/v31/arm64/arch-arm64-armv8-a/shared/vndk-core/android.hardware.soundtrigger@2.0-core.so:$(TARGET_COPY_OUT_VENDOR)/lib64/android.hardware.soundtrigger@2.0-core.so \
    prebuilts/vndk/v31/arm64/arch-arm64-armv8-a/shared/vndk-core/android.hardware.soundtrigger@2.0.so:$(TARGET_COPY_OUT_VENDOR)/lib64/android.hardware.soundtrigger@2.0.so \
    prebuilts/vndk/v31/arm64/arch-arm64-armv8-a/shared/vndk-core/android.hardware.vibrator-V2-ndk_platform.so:$(TARGET_COPY_OUT_VENDOR)/lib64/android.hardware.vibrator-V2-ndk_platform.so \
    prebuilts/vndk/v31/arm64/arch-arm64-armv8-a/shared/vndk-core/android.system.keystore2-V1-ndk_platform.so:$(TARGET_COPY_OUT_VENDOR)/lib64/android.system.keystore2-V1-ndk_platform.so \
    prebuilts/vndk/v31/arm64/arch-arm64-armv8-a/shared/vndk-core/android.system.suspend@1.0.so:$(TARGET_COPY_OUT_VENDOR)/lib64/android.system.suspend@1.0.so \
    prebuilts/vndk/v31/arm64/arch-arm64-armv8-a/shared/vndk-core/libcurl.so:$(TARGET_COPY_OUT_VENDOR)/lib64/libcurl.so

$(call inherit-product, $(SRC_TARGET_DIR)/product/languages_full.mk)
