# Copyright (C) 2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit device configuration
$(call inherit-product, device/umidigi/g7tabpro/device.mk)

# Inherit some common Lineage stuff
$(call inherit-product, vendor/lineage/config/common_full_tablet.mk)  # has cellular - md1img modem partition present in BoardConfig.mk, retail listing confirms 4G SIM

PRODUCT_NAME := lineage_g7tabpro
PRODUCT_DEVICE := g7tabpro
PRODUCT_MANUFACTURER := UMIDIGI
PRODUCT_BRAND := UMIDIGI
PRODUCT_MODEL := G7 Tab Pro

# TODO: verify against `adb shell getprop ro.build.fingerprint` from stock ROM
PRODUCT_GMS_CLIENTID_BASE := android-umidigi

TARGET_SCREEN_DENSITY := 200  # 11" 1200x1920 ~206ppi -> tune after first boot

# AOSP's reference/example GNSS implementation (hardware/interfaces/gnss/aidl/default,
# packaged as the com.android.hardware.gnss vendor APEX) gets auto-included and
# collides at install time with our real extracted MTK GNSS HAL + VINTF manifest,
# both trying to install a file named gnss-default.xml
# ("MODULE.TARGET.ETC.gnss-default.xml already defined by
# hardware/interfaces/gnss/aidl/default"). This device has a real hardware GNSS
# HAL from the stock firmware, so drop the generic default in its favor. Placed
# here (not in device.mk) so it's the last thing evaluated after every inherit.
PRODUCT_PACKAGES -= com.android.hardware.gnss
