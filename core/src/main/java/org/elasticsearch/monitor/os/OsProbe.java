/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.monitor.os;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.Constants;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.monitor.Probes;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OsProbe {

    private static final OperatingSystemMXBean osMxBean = ManagementFactory.getOperatingSystemMXBean();

    private static final Method getFreePhysicalMemorySize;
    private static final Method getTotalPhysicalMemorySize;
    private static final Method getFreeSwapSpaceSize;
    private static final Method getTotalSwapSpaceSize;
    private static final Method getSystemLoadAverage;
    private static final Method getSystemCpuLoad;

    static {
        getFreePhysicalMemorySize = getMethod("getFreePhysicalMemorySize");
        getTotalPhysicalMemorySize = getMethod("getTotalPhysicalMemorySize");
        getFreeSwapSpaceSize = getMethod("getFreeSwapSpaceSize");
        getTotalSwapSpaceSize = getMethod("getTotalSwapSpaceSize");
        getSystemLoadAverage = getMethod("getSystemLoadAverage");
        getSystemCpuLoad = getMethod("getSystemCpuLoad");
    }

    /**
     * Returns the amount of free physical memory in bytes.
     */
    public long getFreePhysicalMemorySize() {
        if (getFreePhysicalMemorySize == null) {
            return -1;
        }
        try {
            return (long) getFreePhysicalMemorySize.invoke(osMxBean);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Returns the total amount of physical memory in bytes.
     */
    public long getTotalPhysicalMemorySize() {
        if (getTotalPhysicalMemorySize == null) {
            return -1;
        }
        try {
            return (long) getTotalPhysicalMemorySize.invoke(osMxBean);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Returns the amount of free swap space in bytes.
     */
    public long getFreeSwapSpaceSize() {
        if (getFreeSwapSpaceSize == null) {
            return -1;
        }
        try {
            return (long) getFreeSwapSpaceSize.invoke(osMxBean);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Returns the total amount of swap space in bytes.
     */
    public long getTotalSwapSpaceSize() {
        if (getTotalSwapSpaceSize == null) {
            return -1;
        }
        try {
            return (long) getTotalSwapSpaceSize.invoke(osMxBean);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The system load averages as an array.
     *
     * On Windows, this method returns {@code null}.
     *
     * On Linux, this method should return the 1, 5, and 15-minute load
     * averages. If obtaining these values from {@code /proc/loadavg}
     * fails, the method will fallback to obtaining the 1-minute load
     * average.
     *
     * On macOS, this method should return the 1-minute load average.
     *
     * @return the available system load averages or {@code null}
     */
    final double[] getSystemLoadAverage() {
        if (Constants.WINDOWS) {
            return null;
        } else if (Constants.LINUX) {
            final String procLoadAvg = readProcLoadavg();
            if (procLoadAvg != null) {
                assert procLoadAvg.matches("(\\d+\\.\\d+\\s+){3}\\d+/\\d+\\s+\\d+");
                final String[] fields = procLoadAvg.split("\\s+");
                try {
                    return new double[]{Double.parseDouble(fields[0]), Double.parseDouble(fields[1]), Double.parseDouble(fields[2])};
                } catch (final NumberFormatException e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(String.format(Locale.ROOT, "error parsing /proc/loadavg [%s]", procLoadAvg), e);
                    }
                }
            }
            // fallback
        }

        if (getSystemLoadAverage == null) {
            return null;
        }
        try {
            final double oneMinuteLoadAverage = (double) getSystemLoadAverage.invoke(osMxBean);
            return new double[] { oneMinuteLoadAverage >= 0 ? oneMinuteLoadAverage : -1, -1, -1 };
        } catch (final Exception e) {
            logger.debug("error obtaining system load average", e);
            return null;
        }
    }

    /**
     * The line from {@code /proc/loadavg}. The first three fields are
     * the load averages averaged over 1, 5, and 15 minutes. The fourth
     * field is two numbers separated by a slash, the first is the
     * number of currently runnable scheduling entities, the second is
     * the number of scheduling entities on the system. The fifth field
     * is the PID of the most recently created process.
     *
     * @return the line from {@code /proc/loadavg} or {@code null}
     */
    @SuppressForbidden(reason = "access /proc/loadavg")
    String readProcLoadavg() {
        try {
            final List<String> lines = Files.readAllLines(PathUtils.get("/proc/loadavg"));
            assert lines != null && lines.size() == 1;
            return lines.get(0);
        } catch (final IOException e) {
            logger.debug("error reading /proc/loadavg", e);
            return null;
        }
    }

    public short getSystemCpuPercent() {
        return Probes.getLoadAndScaleToPercent(getSystemCpuLoad, osMxBean);
    }

    /**
     * Reads a file containing a single line.
     *
     * @param path path to the file to read
     * @return the single line
     * @throws IOException if an I/O exception occurs reading the file
     */
    private String readSingleLine(final Path path) throws IOException {
        final List<String> lines = Files.readAllLines(path);
        assert lines != null && lines.size() == 1;
        return lines.get(0);
    }

    // pattern for lines in /proc/self/cgroup
    private static final Pattern CONTROL_GROUP_PATTERN = Pattern.compile("\\d+:([^:,]+(?:,[^:,]+)?):(/.*)");

    /**
     * A map of the control groups to which the Elasticsearch process
     * belongs. Note that this is a map because the control groups can
     * vary from subsystem to subsystem. Additionally, this map can not
     * be cached because a running process can be reclassified.
     *
     * @return a map from subsystems to the control group for the
     * Elasticsearch process.
     * @throws IOException if an I/O exception occurs reading
     * {@code /proc/self/cgroup}
     */
    private Map<String, String> getControlGroups() throws IOException {
        final List<String> lines = readProcSelfCgroup();
        final Map<String, String> controllerMap = new HashMap<>();
        for (final String line : lines) {
            final Matcher matcher = CONTROL_GROUP_PATTERN.matcher(line);
            // note that Matcher#matches must be invoked as
            // matching is lazy; this can not happen in an assert
            // as assertions might not be enabled
            final boolean matches = matcher.matches();
            assert matches : line;
            // at this point we have captured the subsystems and the
            // control group
            final String[] controllers = matcher.group(1).split(",");
            for (final String controller : controllers) {
                controllerMap.put(controller, matcher.group(2));
            }
        }
        return controllerMap;
    }

    /**
     * The lines from {@code /proc/self/cgroup}. This file represents
     * the control groups to which the Elasticsearch process belongs.
     * Each line in this file represents a control group hierarchy of
     * the form
     * <p>
     * {@code \d+:([^:,]+(?:,[^:,]+)?):(/.*)}
     * <p>
     * with the first field representing the hierarchy ID, the second
     * field representing a comma-separated list of the subsystems
     * bound to the hierarchy, and the last field representing the
     * control group.
     *
     * @return the lines from {@code /proc/self/cgroup}
     * @throws IOException if an I/O exception occurs reading
     * {@code /proc/self/cgroup}
     */
    @SuppressForbidden(reason = "access /proc/self/cgroup")
    List<String> readProcSelfCgroup() throws IOException {
        final List<String> lines = Files.readAllLines(PathUtils.get("/proc/self/cgroup"));
        assert lines != null && !lines.isEmpty();
        return lines;
    }

    /**
     * The total CPU time in nanoseconds consumed by all tasks in the
     * cgroup to which the Elasticsearch process belongs for the
     * {@code cpuacct} subsystem.
     *
     * @param controlGroup the control group for the Elasticsearch
     *                     process for the {@code cpuacct} subsystem
     * @return the total CPU time in nanoseconds
     * @throws IOException if an I/O exception occurs reading
     * {@code cpuacct.usage} for the control group
     */
    private long getCgroupCpuAcctUsageNanos(final String controlGroup) throws IOException {
        return Long.parseLong(readSysFsCgroupCpuAcctCpuAcctUsage(controlGroup));
    }

    /**
     * Returns the line from {@code cpuacct.usage} for the control
     * group to which the Elasticsearch process belongs for the
     * {@code cpuacct} subsystem. This line represents the total CPU
     * time in nanoseconds consumed by all tasks in the same control
     * group.
     *
     * @param controlGroup the control group to which the Elasticsearch
     *                     process belongs for the {@code cpuacct}
     *                     subsystem
     * @return the line from {@code cpuacct.usage}
     * @throws IOException if an I/O exception occurs reading
     * {@code cpuacct.usage} for the control group
     */
    @SuppressForbidden(reason = "access /sys/fs/cgroup/cpuacct")
    String readSysFsCgroupCpuAcctCpuAcctUsage(final String controlGroup) throws IOException {
        return readSingleLine(PathUtils.get("/sys/fs/cgroup/cpuacct", controlGroup, "cpuacct.usage"));
    }

    /**
     * The total period of time in microseconds for how frequently the
     * Elasticsearch control group's access to CPU resources will be
     * reallocated.
     *
     * @param controlGroup the control group for the Elasticsearch
     *                     process for the {@code cpuacct} subsystem
     * @return the CFS quota period in microseconds
     * @throws IOException if an I/O exception occurs reading
     * {@code cpu.cfs_period_us} for the control group
     */
    private long getCgroupCpuAcctCpuCfsPeriodMicros(final String controlGroup) throws IOException {
        return Long.parseLong(readSysFsCgroupCpuAcctCpuCfsPeriod(controlGroup));
    }

    /**
     * Returns the line from {@code cpu.cfs_period_us} for the control
     * group to which the Elasticsearch process belongs for the
     * {@code cpu} subsystem. This line represents the period of time
     * in microseconds for how frequently the control group's access to
     * CPU resources will be reallocated.
     *
     * @param controlGroup the control group to which the Elasticsearch
     *                     process belongs for the {@code cpu}
     *                     subsystem
     * @return the line from {@code cpu.cfs_period_us}
     * @throws IOException if an I/O exception occurs reading
     * {@code cpu.cfs_period_us} for the control group
     */
    @SuppressForbidden(reason = "access /sys/fs/cgroup/cpu")
    String readSysFsCgroupCpuAcctCpuCfsPeriod(final String controlGroup) throws IOException {
        return readSingleLine(PathUtils.get("/sys/fs/cgroup/cpu", controlGroup, "cpu.cfs_period_us"));
    }

    /**
     * The total time in microseconds that all tasks in the
     * Elasticsearch control group can run during one period as
     * specified by {@code cpu.cfs_period_us}.
     *
     * @param controlGroup the control group for the Elasticsearch
     *                     process for the {@code cpuacct} subsystem
     * @return the CFS quota in microseconds
     * @throws IOException if an I/O exception occurs reading
     * {@code cpu.cfs_quota_us} for the control group
     */
    private long getCGroupCpuAcctCpuCfsQuotaMicros(final String controlGroup) throws IOException {
        return Long.parseLong(readSysFsCgroupCpuAcctCpuAcctCfsQuota(controlGroup));
    }

    /**
     * Returns the line from {@code cpu.cfs_quota_us} for the control
     * group to which the Elasticsearch process belongs for the
     * {@code cpu} subsystem. This line represents the total time in
     * microseconds that all tasks in the control group can run during
     * one period as specified by {@code cpu.cfs_period_us}.
     *
     * @param controlGroup the control group to which the Elasticsearch
     *                     process belongs for the {@code cpu}
     *                     subsystem
     * @return the line from {@code cpu.cfs_quota_us}
     * @throws IOException if an I/O exception occurs reading
     * {@code cpu.cfs_quota_us} for the control group
     */
    @SuppressForbidden(reason = "access /sys/fs/cgroup/cpu")
    String readSysFsCgroupCpuAcctCpuAcctCfsQuota(final String controlGroup) throws IOException {
        return readSingleLine(PathUtils.get("/sys/fs/cgroup/cpu", controlGroup, "cpu.cfs_quota_us"));
    }

    /**
     * The CPU time statistics for all tasks in the Elasticsearch
     * control group.
     *
     * @param controlGroup the control group for the Elasticsearch
     *                     process for the {@code cpuacct} subsystem
     * @return the CPU time statistics
     * @throws IOException if an I/O exception occurs reading
     * {@code cpu.stat} for the control group
     */
    private OsStats.Cgroup.CpuStat getCgroupCpuAcctCpuStat(final String controlGroup) throws IOException {
        final List<String> lines = readSysFsCgroupCpuAcctCpuStat(controlGroup);
        long numberOfPeriods = -1;
        long numberOfTimesThrottled = -1;
        long timeThrottledNanos = -1;
        for (final String line : lines) {
            final String[] fields = line.split("\\s+");
            switch (fields[0]) {
                case "nr_periods":
                    numberOfPeriods = Long.parseLong(fields[1]);
                    break;
                case "nr_throttled":
                    numberOfTimesThrottled = Long.parseLong(fields[1]);
                    break;
                case "throttled_time":
                    timeThrottledNanos = Long.parseLong(fields[1]);
                    break;
            }
        }
        assert numberOfPeriods != -1;
        assert numberOfTimesThrottled != -1;
        assert timeThrottledNanos != -1;
        return new OsStats.Cgroup.CpuStat(numberOfPeriods, numberOfTimesThrottled, timeThrottledNanos);
    }

    /**
     * Returns the lines from {@code cpu.stat} for the control
     * group to which the Elasticsearch process belongs for the
     * {@code cpu} subsystem. These lines represent the CPU time
     * statistics and have the form
     *
     * nr_periods \d+
     * nr_throttled \d+
     * throttled_time \d+
     *
     * where {@code nr_periods} is the number of period intervals
     * as specified by {@code cpu.cfs_period_us} that have elapsed,
     * {@code nr_throttled} is the number of times tasks in the given
     * control group have been throttled, and {@code throttled_time} is
     * the total time in nanoseconds for which tasks in the given
     * control group have been throttled.
     *
     * @param controlGroup the control group to which the Elasticsearch
     *                     process belongs for the {@code cpu}
     *                     subsystem
     *
     * @return the lines from {@code cpu.stat}
     * @throws IOException if an I/O exception occurs reading
     * {@code cpu.stat} for the control group
     */
    @SuppressForbidden(reason = "access /sys/fs/cgroup/cpu")
    List<String> readSysFsCgroupCpuAcctCpuStat(final String controlGroup) throws IOException {
        final List<String> lines = Files.readAllLines(PathUtils.get("/sys/fs/cgroup/cpu", controlGroup, "cpu.stat"));
        assert lines != null && lines.size() == 3;
        return lines;
    }

    /**
     * Basic cgroup stats.
     *
     * @return basic cgroup stats, or {@code null} if an I/O exception
     * occurred reading the cgroup stats
     */
    private OsStats.Cgroup getCgroup() {
        try {
            final Map<String, String> controllerMap = getControlGroups();
            final String cpuControlGroup = controllerMap.get("cpu");
            final String cpuAcctControlGroup = controllerMap.get("cpuacct");
            return new OsStats.Cgroup(
                cpuAcctControlGroup,
                getCgroupCpuAcctUsageNanos(cpuAcctControlGroup),
                cpuControlGroup,
                getCgroupCpuAcctCpuCfsPeriodMicros(cpuControlGroup),
                getCGroupCpuAcctCpuCfsQuotaMicros(cpuControlGroup),
                getCgroupCpuAcctCpuStat(cpuControlGroup));
        } catch (final IOException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("error reading control group stats", e);
            }
            return null;
        }
    }

    private static class OsProbeHolder {
        private static final OsProbe INSTANCE = new OsProbe();
    }

    public static OsProbe getInstance() {
        return OsProbeHolder.INSTANCE;
    }

    OsProbe() {

    }

    private final Logger logger = ESLoggerFactory.getLogger(getClass());

    public OsInfo osInfo(long refreshInterval, int allocatedProcessors) {
        return new OsInfo(refreshInterval, Runtime.getRuntime().availableProcessors(),
                allocatedProcessors, Constants.OS_NAME, Constants.OS_ARCH, Constants.OS_VERSION);
    }

    public OsStats osStats() {
        final OsStats.Cpu cpu = new OsStats.Cpu(getSystemCpuPercent(), getSystemLoadAverage());
        final OsStats.Mem mem = new OsStats.Mem(getTotalPhysicalMemorySize(), getFreePhysicalMemorySize());
        final OsStats.Swap swap = new OsStats.Swap(getTotalSwapSpaceSize(), getFreeSwapSpaceSize());
        final OsStats.Cgroup cgroup = Constants.LINUX ? getCgroup() : null;
        return new OsStats(System.currentTimeMillis(), cpu, mem, swap, cgroup);
    }

    /**
     * Returns a given method of the OperatingSystemMXBean,
     * or null if the method is not found or unavailable.
     */
    private static Method getMethod(String methodName) {
        try {
            return Class.forName("com.sun.management.OperatingSystemMXBean").getMethod(methodName);
        } catch (Exception e) {
            // not available
            return null;
        }
    }

}
