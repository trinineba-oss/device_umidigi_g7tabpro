# Copyright (C) 2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

LOCAL_PATH := device/umidigi/g7tabpro

# Inherit the proprietary blobs (populated by extract-files.sh, see Step 5 in README)
$(call inherit-product, vendor/umidigi/g7tabpro/device-vendor.mk)

PRODUCT_CHARACTERISTICS := tablet

# Architecture
PRODUCT_PROPERTY_OVERRIDES += \
    ro.zygote=zygote64_32

# Kernel/dtbo/boot
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/rootdir/etc/fstab.mt6789:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.mt6789 \
    $(LOCAL_PATH)/rootdir/etc/fstab.mt6789:$(TARGET_COPY_OUT_RAMDISK)/first_stage_ramdisk/fstab.mt6789

# Display / graphics — MT6789 uses Mali-G57 MC2, verify HAL package names
# against the donor tree you forked (Step 4 in README) once you pick one.
PRODUCT_PACKAGES += \
    android.hardware.graphics.allocator@4.0-service \
    android.hardware.graphics.composer@2.4-service

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

$(call inherit-product, $(SRC_TARGET_DIR)/product/languages_full.mk)
