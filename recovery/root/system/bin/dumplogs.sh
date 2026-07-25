#!/system/bin/sh
# Diagnostic dump for TWRP bring-up: writes logs to the microSD card so they
# can be read without adb. Runs automatically at boot; see
# init.recovery.mt6789.rc. Safe to remove once adb works.

# Give TWRP time to fully start (or hang) before capturing.
sleep 35

OUT=/tmp/sdlog
mkdir -p $OUT

# Try the usual microSD device nodes; first one that mounts wins.
for DEV in /dev/block/mmcblk1p1 /dev/block/mmcblk1 /dev/block/mmcblk0p1; do
    mount -t vfat $DEV $OUT 2>/dev/null && break
    mount $DEV $OUT 2>/dev/null && break
done

D=$OUT/twrplogs
mkdir -p $D

dmesg                          > $D/dmesg.txt 2>&1
cp /tmp/recovery.log             $D/recovery.log 2>&1
ps -A                          > $D/ps.txt 2>&1
lsmod                          > $D/lsmod.txt 2>&1
getevent -p                    > $D/getevent.txt 2>&1
cat /proc/bus/input/devices    > $D/input_devices.txt 2>&1
ls -la /dev/input/             > $D/dev_input.txt 2>&1
ls /proc/device-tree/soc/i2c@11e00000/ > $D/i2c0_node.txt 2>&1
cat /proc/interrupts           > $D/interrupts.txt 2>&1
getprop                        > $D/getprop.txt 2>&1

# Second capture 20s later, to show whether anything is progressing or frozen.
sleep 20
dmesg                          > $D/dmesg_later.txt 2>&1
cp /tmp/recovery.log             $D/recovery_later.log 2>&1
ps -A                          > $D/ps_later.txt 2>&1

sync
umount $OUT 2>/dev/null
