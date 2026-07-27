#!/system/bin/sh
# Diagnostic dump v4 — tests whether a manual display blank/unblank cycle
# triggers the touch driver's deferred input registration.
#
# Evidence: a working-system trace showed the driver's "Resume"/"Start
# device" sequence firing from fts_disp_notifier_callback — i.e. gated on a
# DISPLAY notifier event, not called unconditionally at probe. tddi_9551.ko
# depends on mtk_disp_notify.ko (confirmed in modules.dep). TWRP's DRM usage
# is a single atomic commit with no ongoing compositor, so that notifier may
# never fire here. This script forces one manually, via whichever interface
# exists.

sleep 25

# Snapshot BEFORE any trigger attempt
mkdir -p /tmp/pre
cat /proc/bus/input/devices > /tmp/pre_input.txt 2>&1
readlink /sys/bus/i2c/devices/0-0048/driver > /tmp/pre_driver.txt 2>&1

# --- Attempt 1: legacy fbdev blank/unblank (propagates through
# fb_notifier_call_chain, which some MTK trees bridge to disp_notify) ---
for FB in /sys/class/graphics/fb0/blank /sys/class/graphics/fb1/blank; do
    if [ -e "$FB" ]; then
        echo "FB_BLANK trigger via $FB" >> /tmp/trigger_log.txt
        echo 1 > "$FB" 2>>/tmp/trigger_log.txt
        sleep 1
        echo 0 > "$FB" 2>>/tmp/trigger_log.txt
    fi
done

# --- Attempt 2: DRM connector force (secondary; not expected to hit the
# same notifier, but cheap to try) ---
for ST in /sys/class/drm/*/status; do
    [ -e "$ST" ] && cat "$ST" >> /tmp/drm_status_before.txt 2>&1
done
for F in /sys/class/drm/*/force; do
    if [ -e "$F" ]; then
        echo "DRM force trigger via $F" >> /tmp/trigger_log.txt
        echo "off" > "$F" 2>>/tmp/trigger_log.txt
        sleep 1
        echo "on" > "$F" 2>>/tmp/trigger_log.txt
    fi
done

sleep 3

OUT=/tmp/sdlog
mkdir -p $OUT
for DEV in /dev/block/mmcblk1p1 /dev/block/mmcblk1 /dev/block/mmcblk0p1; do
    mount -t vfat $DEV $OUT 2>/dev/null && break
    mount $DEV $OUT 2>/dev/null && break
done
D=$OUT/twrplogs
mkdir -p $D

cp /tmp/trigger_log.txt          $D/ 2>/dev/null
cp /tmp/pre_input.txt            $D/ 2>/dev/null
cp /tmp/pre_driver.txt           $D/ 2>/dev/null
cp /tmp/drm_status_before.txt    $D/ 2>/dev/null
ls /sys/class/graphics/         > $D/fb_devices_available.txt 2>&1
ls /sys/class/drm/              > $D/drm_devices_available.txt 2>&1

dmesg                           > $D/dmesg.txt 2>&1
cp /tmp/recovery.log              $D/recovery.log 2>&1
cat /proc/bus/input/devices     > $D/input_devices_AFTER.txt 2>&1
getevent -p                     > $D/getevent_AFTER.txt 2>&1
readlink /sys/bus/i2c/devices/0-0048/driver > $D/driver_AFTER.txt 2>&1

sleep 20
dmesg                           > $D/dmesg_later.txt 2>&1
cat /proc/bus/input/devices     > $D/input_devices_LATER.txt 2>&1

sync
umount $OUT 2>/dev/null
