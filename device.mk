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

$(call inherit-product, $(SRC_TARGET_DIR)/product/languages_full.mk)
