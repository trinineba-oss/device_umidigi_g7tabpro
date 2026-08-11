# UMIDIGI G7 Tab Pro (g7tabpro) - TWRP Device Tree

TWRP device tree for the UMIDIGI G7 Tab Pro: MediaTek Helio G99 (MT6789),
Android 13, 8GB/256GB, 11" 1200x1920. Built from scratch; no public device
tree existed for this tablet.

## Status: working

| Item | State |
|---|---|
| Builds (twrp-12.1 minimal manifest) | yes |
| Boots to menu, graphics correct | yes |
| Touchscreen | yes (binary patch + rotation fix) |
| microSD mounts - PC-free ROM flashing | yes |
| adb stable at the menu | yes (TW_EXCLUDE_MTP) |
| /data decryption in recovery | no - deliberate trade-off (FBE hang) |

## Hardware

| Item | Value |
|---|---|
| SoC | MT6789 (Helio G99), Mali-G57 MC2 |
| Kernel | 5.10.185 GKI, boot header v4 (prebuilt; no GPL source released) |
| Partitions | A/B, dynamic (super 9.0 GB), no recovery partition |
| Recovery | boot-as-recovery via vendor_boot |
| Panel / Touch | l0a9w006c_dsi_vdo / Chipone TDDI (tddi_9551.ko, i2c0 @0x48) |
| TEE | TrustKernel |

## Build

    repo init --depth=1 -u https://github.com/minimal-manifest-twrp/platform_manifest_twrp_aosp.git -b twrp-12.1
    repo sync
    mkdir -p device/umidigi
    cp -r device_umidigi_g7tabpro device/umidigi/g7tabpro
    export ALLOW_MISSING_DEPENDENCIES=true
    . build/envsetup.sh
    lunch twrp_g7tabpro-eng
    mka vendorbootimage

Flash only vendor_boot.img:

    fastboot flash vendor_boot vendor_boot.img
    fastboot reboot recovery

vbmeta: custom TWRP needs verification disabled; stock/LOS20 need it enabled.
Never flash erased vbmeta.

## The two non-obvious fixes

1. tddi_9551.ko binary patch at offset 0x1c28 (340001e8 -> 1400000f).
   The driver refuses to register input unless atag,boot == 0, and the
   bootloader rewrites every atag,boot in the FDT to 2 in recovery, so no
   DTS-based fix can work. Verify with:
   xxd -s 0x1c28 -l 4 prebuilt/modules/tddi_9551.ko  ->  0f00 0014

2. TW_EXCLUDE_MTP := true - MTP steals the USB gadget at the TWRP menu and
   kills adb.

## GSI notes

The "vendor.mediatek.hardware.pq@2.14 not found" theory for GSI boot
failures was disproven. The real blocker for LineageOS 21 class GSIs is
keystore2 / KeyMint (KEYMINT_NOT_CONFIGURED, -64) on units with a wiped
TrustKernel TEE. LineageOS 20 TD and PeterGSI builds boot fine.

## References

- MT6789-Rock/device_xiaomi_rock (same SoC)
- transsion-mt6789-recovery/twrp-device_tecno_TECNO-LI7

## License

Apache-2.0
