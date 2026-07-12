# Copyright (C) 2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#
# Product makefile for TWRP builds specifically, targeting the twrp-12.1
# minimal manifest (github.com/minimal-manifest-twrp/platform_manifest_twrp_aosp),
# which is current for Android 10+ devices including this Android 13 one.
# Deliberately standalone — does NOT inherit device.mk (that pulls in
# full ROM-side HAL packages/vendor blobs that don't exist in the TWRP
# minimal source tree and aren't needed to build a recovery ramdisk).

# Inherit base product config
$(call inherit-product, $(SRC_TARGET_DIR)/product/embedded.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)

# Inherit TWRP's common product config — provides default TWRP packages,
# binaries, and build rules. Confirm this path exists after repo sync;
# minimal-manifest-twrp has occasionally moved it between branches.
$(call inherit-product, vendor/twrp/config/common.mk)

PRODUCT_NAME := twrp_g7tabpro
PRODUCT_DEVICE := g7tabpro
PRODUCT_BRAND := UMIDIGI
PRODUCT_MODEL := G7 Tab Pro
PRODUCT_MANUFACTURER := UMIDIGI

BOARD_VENDOR := umidigi
