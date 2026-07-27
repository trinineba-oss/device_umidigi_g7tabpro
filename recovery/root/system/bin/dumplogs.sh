#!/system/bin/sh
# Diagnostic dump v5 — v4's triggers didn't exist (no /sys/class/graphics/,
# no drm */force file matched). This properly enumerates what IS available
# on card0-DSI-1 and mediatek_drm's debugfs before attempting anything, then
# tries the correct DRM force path directly.

sleep 25

D0=/sys/class/drm/card0-DSI-1
ls -la $D0/ > /tmp/dsi1_dir.txt 2>&1
cat $D0/status > /tmp/status_before.txt 2>&1
[ -e $D0/force ] && echo "force EXISTS" > /tmp/force_exists.txt || echo "force MISSING" > /tmp/force_exists.txt
[ -e $D0/dpms ] && cat $D0/dpms > /tmp/dpms_before.txt 2>&1

ls -la /sys/kernel/debug/dri/ > /tmp/debugfs_dri.txt 2>&1
ls -la /sys/kernel/debug/dri/0/ > /tmp/debugfs_dri0.txt 2>&1 
find /sys/kernel/debug/dri -iname "*panel*" -o -iname "*disp*" -o -iname "*power*" > /tmp/debugfs_relevant.txt 2>&1

mkdir -p /tmp/pre
cat /proc/bus/input/devices > /tmp/pre_input.txt 2>&1

# Try dpms cycle if it exists (0=on, 3=off — this is the real DRM legacy
# "power off/on the display" knob, closer to what a compositor does than
# force ever was)
if [ -e $D0/dpms ]; then
    echo "3" > $D0/dpms 2>>/tmp/trigger_log.txt
    sleep 1
    echo "0" > $D0/dpms 2>>/tmp/trigger_log.txt
    echo "dpms cycle attempted" >> /tmp/trigger_log.txt
fi

# Try force as well, now that we've confirmed whether it exists
if [ -e $D0/force ]; then
    echo "on" > $D0/force 2>>/tmp/trigger_log.txt
    echo "force write attempted" >> /tmp/trigger_log.txt
fi

sleep 3

OUT=/tmp/sdlog
mkdir -p $OUT
for DEV in /dev/block/mmcblk1p1 /dev/block/mmcblk1 /dev/block/mmcblk0p1; do
    mount -t vfat $DEV $OUT 2>/dev/null && break
    mount $DEV $OUT 2>/dev/null && break
done
D=$OUT/twrplogs
mkdir -p $D

cp /tmp/dsi1_dir.txt $D/ 2>/dev/null
cp /tmp/status_before.txt $D/ 2>/dev/null
cp /tmp/force_exists.txt $D/ 2>/dev/null
cp /tmp/dpms_before.txt $D/ 2>/dev/null
cp /tmp/debugfs_dri.txt $D/ 2>/dev/null
cp /tmp/debugfs_dri0.txt $D/ 2>/dev/null
cp /tmp/debugfs_relevant.txt $D/ 2>/dev/null
cp /tmp/trigger_log.txt $D/ 2>/dev/null
cp /tmp/pre_input.txt $D/ 2>/dev/null

dmesg                            > $D/dmesg.txt 2>&1
cp /tmp/recovery.log               $D/recovery.log 2>&1
cat /proc/bus/input/devices      > $D/input_devices_AFTER.txt 2>&1
getevent -p                      > $D/getevent_AFTER.txt 2>&1
readlink /sys/bus/i2c/devices/0-0048/driver > $D/driver_AFTER.txt 2>&1
cat $D0/status                   > $D/status_after.txt 2>&1

sleep 15
dmesg                            > $D/dmesg_later.txt 2>&1
cat /proc/bus/input/devices      > $D/input_devices_LATER.txt 2>&1

sync
umount $OUT 2>/dev/null
