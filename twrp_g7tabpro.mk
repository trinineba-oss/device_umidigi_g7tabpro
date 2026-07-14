# Copyright (C) 2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#
# Inherit chain matches real, currently-building twrp-12.1 device trees
# (miatoll, denver, samsung a15 — also Helio G99/MT6789). The minimal
# manifest's build/make is trimmed vs full AOSP; core_64_bit.mk +
# embedded.mk don't both exist in it. full_base_telephony.mk does.
#
# Deliberately does NOT inherit device.mk — that's for the full ROM
# build and needs extract-files.sh run first.
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit some common TWRP stuff.
$(call inherit-product, vendor/twrp/config/common.mk)

PRODUCT_NAME := twrp_g7tabpro
PRODUCT_DEVICE := g7tabpro
PRODUCT_BRAND := UMIDIGI
PRODUCT_MODEL := G7 Tab Pro
PRODUCT_MANUFACTURER := UMIDIGI

BOARD_VENDOR := umidigi

# Fix: first_stage_ramdisk was empty - copy fstab in explicitly
PRODUCT_COPY_FILES += \
    device/umidigi/g7tabpro/rootdir/etc/fstab.mt6789:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/fstab.mt6789
