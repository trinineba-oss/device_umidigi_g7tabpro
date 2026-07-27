#!/system/bin/sh
# Diagnostic dump v3 — direct driver-binding check, bypassing printk entirely
# (MTK's mtk_printk_ctrl may be suppressing <I>/<W>/<E>-prefixed driver logs,
# which would explain empty cts_msgs.txt regardless of what the driver does).

sleep 35
OUT=/tmp/sdlog
mkdir -p $OUT
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

# --- DIRECT driver-binding check, no printk dependency ---
ls -la /sys/bus/i2c/devices/0-0048/          > $D/dev_0048_dir.txt 2>&1
ls -la /sys/bus/i2c/devices/0-0048/driver    > $D/dev_0048_driver_link.txt 2>&1
readlink /sys/bus/i2c/devices/0-0048/driver  > $D/dev_0048_driver_target.txt 2>&1
cat /sys/bus/i2c/devices/0-0048/modalias     > $D/dev_0048_modalias.txt 2>&1
ls -la /sys/bus/i2c/drivers/chipone_tddi/    > $D/driver_chipone_dir.txt 2>&1
ls -la /sys/module/tddi_9551/                > $D/module_dir.txt 2>&1
cat /sys/module/tddi_9551/parameters/debug_log > $D/debug_log_param.txt 2>&1
cat /sys/kernel/debug/printk_ctrl/* > $D/printk_ctrl.txt 2>&1
cat /proc/sys/kernel/printk > $D/kernel_printk_level.txt 2>&1

# force max verbosity + disable any MTK printk suppression, retry logging
echo 8 > /proc/sys/kernel/printk 2>/dev/null
echo 1 > /sys/module/tddi_9551/parameters/debug_log 2>/dev/null
echo "" > /proc/sysrq-trigger 2>/dev/null

sleep 20
dmesg                          > $D/dmesg_later.txt 2>&1
cp /tmp/recovery.log             $D/recovery_later.log 2>&1
ps -A                          > $D/ps_later.txt 2>&1
readlink /sys/bus/i2c/devices/0-0048/driver  > $D/dev_0048_driver_target_later.txt 2>&1

sync
umount $OUT 2>/dev/null
