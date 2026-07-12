# Copyright (C) 2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit device configuration
$(call inherit-product, device/umidigi/g7tabpro/device.mk)

# Inherit some common Lineage stuff
$(call inherit-product, vendor/lineage/config/common_tablet_wifionly.mk)  # TODO: remove _wifionly if this SKU has cellular

PRODUCT_NAME := lineage_g7tabpro
PRODUCT_DEVICE := g7tabpro
PRODUCT_MANUFACTURER := UMIDIGI
PRODUCT_BRAND := UMIDIGI
PRODUCT_MODEL := G7 Tab Pro

# TODO: verify against `adb shell getprop ro.build.fingerprint` from stock ROM
PRODUCT_GMS_CLIENTID_BASE := android-umidigi

TARGET_SCREEN_DENSITY := 200  # 11" 1200x1920 ~206ppi -> tune after first boot
