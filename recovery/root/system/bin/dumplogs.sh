#!/system/bin/sh
# Diagnostic dump v6 — confirms whether debug_log=1 actually took effect.
# v5 never checked this, so we don't yet know if modules.options is being
# applied at all, or if it applied but debug_log doesn't gate the messages
# we need. This checks BEFORE any action, then also tries forcing it via
# direct sysfs write as a fallback (in case load-time modules.options isn't
# honored by this recovery's module loader).

sleep 25

# What debug_log actually is, straight from modules.options loading
cat /sys/module/tddi_9551/parameters/debug_log > /tmp/debug_log_from_options.txt 2>&1

# Fallback: force it at runtime regardless, in case modules.options wasn't
# applied. If this write fails, the parameter is likely read-only after init
# (which would mean modules.options really is the only mechanism, and its
# failure to take effect points elsewhere).
echo 1 > /sys/module/tddi_9551/parameters/debug_log 2>/tmp/debug_log_write_error.txt
cat /sys/module/tddi_9551/parameters/debug_log > /tmp/debug_log_after_force.txt 2>&1

# Try to force a re-probe by unbinding/rebinding — if the driver's logging IS
# now on, this should produce a fresh, fully-logged probe attempt.
echo "0-0048" > /sys/bus/i2c/drivers/chipone_tddi/unbind 2>/tmp/unbind_result.txt
sleep 1
echo "0-0048" > /sys/bus/i2c/drivers/chipone_tddi/bind 2>/tmp/bind_result.txt
sleep 2

OUT=/tmp/sdlog
mkdir -p $OUT
for DEV in /dev/block/mmcblk1p1 /dev/block/mmcblk1 /dev/block/mmcblk0p1; do
    mount -t vfat $DEV $OUT 2>/dev/null && break
    mount $DEV $OUT 2>/dev/null && break
done
D=$OUT/twrplogs
mkdir -p $D

cp /tmp/debug_log_from_options.txt $D/ 2>/dev/null
cp /tmp/debug_log_write_error.txt  $D/ 2>/dev/null
cp /tmp/debug_log_after_force.txt  $D/ 2>/dev/null
cp /tmp/unbind_result.txt          $D/ 2>/dev/null
cp /tmp/bind_result.txt            $D/ 2>/dev/null

dmesg                             > $D/dmesg.txt 2>&1
cp /tmp/recovery.log                $D/recovery.log 2>&1
cat /proc/bus/input/devices       > $D/input_devices.txt 2>&1
getevent -p                       > $D/getevent.txt 2>&1
readlink /sys/bus/i2c/devices/0-0048/driver > $D/driver_state.txt 2>&1
dmesg | grep -iE "cts|chipone|tddi" > $D/cts_msgs.txt 2>&1

sleep 15
dmesg                             > $D/dmesg_later.txt 2>&1
cat /proc/bus/input/devices       > $D/input_devices_later.txt 2>&1

sync
umount $OUT 2>/dev/null
