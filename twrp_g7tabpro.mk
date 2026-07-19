# Copyright (C) 2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#
# Product makefile for TWRP builds specifically, targeting the twrp-12.1
# minimal manifest (github.com/minimal-manifest-twrp/platform_manifest_twrp_aosp).
#
# Inherit chain matches what real, currently-building twrp-12.1 device
# trees use (cross-checked against twrp_device_xiaomi_miatoll,
# twrp12_device_motorola_denver, android_device_samsung_a15 — the last one
# is also a Helio G99/MT6789-class device). The minimal manifest's
# build/make is heavily trimmed vs full AOSP — core_64_bit.mk +
# embedded.mk (what this file used before) don't both exist in it and
# broke the build with "embedded.mk does not exist". full_base_telephony.mk
# does exist and is what every working example actually uses.
#
# Deliberately does NOT inherit device.mk — that's written for the full
# ROM build and pulls in vendor blob inheritance that doesn't exist yet
# (extract-files.sh hasn't been run) and isn't needed to build a recovery
# ramdisk anyway.
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit some common TWRP stuff.
$(call inherit-product, vendor/twrp/config/common.mk)

PRODUCT_NAME := twrp_g7tabpro
PRODUCT_DEVICE := g7tabpro
PRODUCT_BRAND := UMIDIGI
PRODUCT_MODEL := G7 Tab Pro
PRODUCT_MANUFACTURER := UMIDIGI

BOARD_VENDOR := umidigi

# CONFIRMED fix for a real build failure: first_stage_ramdisk was
# completely empty (0 files) in the built vendor_boot.img, compared
# against a working reference build (29 files, including this exact
# fstab). TARGET_RECOVERY_FSTAB in BoardConfig.mk only tells the build
# tools which fstab to reference — it does NOT copy the file into the
# ramdisk. This line does the actual copy, per AOSP's own documented
# pattern for GKI/no-recovery-partition devices:
# https://source.android.com/docs/core/architecture/partitions/generic-boot
PRODUCT_COPY_FILES += \
    device/umidigi/g7tabpro/rootdir/etc/fstab.mt6789:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/fstab.mt6789

# These early-boot binaries (e2fsck, linker64, snapuserd + shared libs,
# AVB GSI pubkeys) aren't something we can build from source in the TWRP
# minimal manifest — extracted directly from a working reference build
# (Hovatek's twrp.img, confirmed booting on this exact tablet) instead.
# BUILD_BROKEN_ELF_PREBUILT_PRODUCT_COPY_FILES in BoardConfig.mk is what
# allows copying raw prebuilt ELF binaries this way without the build
# system rejecting them for missing build-time dependency info.
PRODUCT_COPY_FILES += \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/avb/q-gsi.avbpubkey:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/avb/q-gsi.avbpubkey \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/avb/r-gsi.avbpubkey:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/avb/r-gsi.avbpubkey \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/avb/s-gsi.avbpubkey:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/avb/s-gsi.avbpubkey \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/bin/e2fsck:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/bin/e2fsck \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/bin/linker64:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/bin/linker64 \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/bin/snapuserd:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/bin/snapuserd \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/lib64/ld-android.so:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/lib64/ld-android.so \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/lib64/libbase.so:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/lib64/libbase.so \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/lib64/libc++.so:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/lib64/libc++.so \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/lib64/libc.so:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/lib64/libc.so \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/lib64/libdl.so:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/lib64/libdl.so \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/lib64/libext2_blkid.so:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/lib64/libext2_blkid.so \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/lib64/libext2_com_err.so:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/lib64/libext2_com_err.so \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/lib64/libext2_e2p.so:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/lib64/libext2_e2p.so \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/lib64/libext2_quota.so:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/lib64/libext2_quota.so \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/lib64/libext2_uuid.so:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/lib64/libext2_uuid.so \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/lib64/libext2fs.so:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/lib64/libext2fs.so \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/lib64/liblog.so:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/lib64/liblog.so \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/lib64/libm.so:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/lib64/libm.so \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/lib64/libsparse.so:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/lib64/libsparse.so \
    device/umidigi/g7tabpro/prebuilt/first_stage_ramdisk/system/lib64/libz.so:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/system/lib64/libz.so
