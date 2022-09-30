/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.SystemResourceController;
import org.apache.commons.lang3.StringUtils;
import org.zeroturnaround.process.PidUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.apache.commons.io.FileUtils.ONE_KB;

public class LinuxSystemResourceControllerV2 implements SystemResourceController {

    private static final Logger logger = LogManager.getLogger(LinuxSystemResourceControllerV2.class);
    private static final String COMPONENT_NAME = "componentName";
    private static final String MEMORY_KEY = "memory";
    private static final String CPUS_KEY = "cpus";

    private static final String UNICODE_SPACE = "\\040";
    private static final String CGROUP_SUBTREE_CONTROL_CONTENT = "+cpuset +cpu +io +memory +pids";

    protected final LinuxPlatform platform;

    public LinuxSystemResourceControllerV2(LinuxPlatform platform) {
        this.platform = platform;
    }

    @Override
    public void removeResourceController(GreengrassService component) {
        try {
            // Assumes processes belonging to cgroups would already be terminated/killed.
            Files.deleteIfExists(CgroupV2.getSubsystemComponentPath(component.getServiceName()));
        } catch (IOException e) {
            logger.atError().setCause(e).kv(COMPONENT_NAME, component.getServiceName())
                    .log("Failed to remove the resource controller");
        }
    }

    @Override
    public void updateResourceLimits(GreengrassService component, Map<String, Object> resourceLimit) {
        try {
            if (!Files.exists(CgroupV2.getSubsystemComponentPath(component.getServiceName()))) {
                initializeCgroup(component);
            }

            if (resourceLimit.containsKey(MEMORY_KEY)) {
                long memoryLimitInKB = Coerce.toLong(resourceLimit.get(MEMORY_KEY));

                if (memoryLimitInKB > 0) {
                    String memoryLimit = Long.toString(memoryLimitInKB * ONE_KB);
                    Files.write(CgroupV2.getComponentMemoryMaxPath(component.getServiceName()),
                            memoryLimit.getBytes(StandardCharsets.UTF_8));
                } else {
                    logger.atWarn().kv(COMPONENT_NAME, component.getServiceName()).kv(MEMORY_KEY, memoryLimitInKB)
                            .log("The provided memory limit is invalid");
                }
            }

            if (resourceLimit.containsKey(CPUS_KEY)) {
                double cpu = Coerce.toDouble(resourceLimit.get(CPUS_KEY));
                if (cpu > 0) {
                    byte[] content = Files.readAllBytes(
                            CgroupV2.getComponentCpuMaxPath(component.getServiceName()));
                    String cpuMaxContent = new String(content, StandardCharsets.UTF_8).trim();
                    String[] cpuMaxContentArr = cpuMaxContent.split(" ");
                    String cpuMaxStr = "max";
                    String cpuPeriodStr = "100000";

                    if (cpuMaxContentArr.length >= 2) {
                        cpuMaxStr = cpuMaxContentArr[0];
                        cpuPeriodStr = cpuMaxContentArr[1];

                        if (!StringUtils.isEmpty(cpuPeriodStr)) {
                            int period = Integer.parseInt(cpuPeriodStr.trim());
                            int max = (int) (period * cpu);
                            cpuMaxStr = Integer.toString(max);
                        }
                    }

                    String latestCpuMaxContent = String.format("%s %s", cpuMaxStr, cpuPeriodStr);
                    Files.write(CgroupV2.getComponentCpuMaxPath(component.getServiceName()),
                            latestCpuMaxContent.getBytes(StandardCharsets.UTF_8));
                } else {
                    logger.atWarn().kv(COMPONENT_NAME, component.getServiceName()).kv(CPUS_KEY, cpu)
                            .log("The provided cpu limit is invalid");
                }
            }
        } catch (IOException e) {
            logger.atError().setCause(e).kv(COMPONENT_NAME, component.getServiceName())
                    .log("Failed to apply resource limits");
        }
    }

    @Override
    public void resetResourceLimits(GreengrassService component) {
        try {
            if (Files.exists(CgroupV2.getSubsystemComponentPath(component.getServiceName()))) {
                Files.delete(CgroupV2.getSubsystemComponentPath(component.getServiceName()));
                Files.createDirectory(CgroupV2.getSubsystemComponentPath(component.getServiceName()));
            }
        } catch (IOException e) {
            logger.atError().setCause(e).kv(COMPONENT_NAME, component.getServiceName())
                    .log("Failed to remove the resource controller");
        }
    }

    @Override
    public void addComponentProcess(GreengrassService component, Process process) {
        try {
            addComponentProcessToCgroup(component.getServiceName(), process);

            // Child processes of a process in a cgroup are auto-added to the same cgroup by linux kernel. But in
            // case of a race condition in starting a child process and us adding pids to cgroup, neither us nor
            // the linux kernel will add it to the cgroup. To account for this, re-list all pids for the component
            // after 1 second and add to cgroup again so that all component processes are resource controlled.
            component.getContext().get(ScheduledExecutorService.class).schedule(() -> {
                try {
                    addComponentProcessToCgroup(component.getServiceName(), process);
                } catch (IOException e) {
                    handleErrorAddingPidToCgroup(e, component.getServiceName());
                }
            }, 1, TimeUnit.SECONDS);

        } catch (IOException e) {
            handleErrorAddingPidToCgroup(e, component.getServiceName());
        }
    }

    @Override
    public void pauseComponentProcesses(GreengrassService component, List<Process> processes) throws IOException {
        initializeCgroup(component);

        for (Process process : processes) {
            addComponentProcessToCgroup(component.getServiceName(), process);
        }

        Files.write(freezerCgroupStateFile(component.getServiceName()),
                String.valueOf(CgroupV2FreezerState.FROZEN.getIndex()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public void resumeComponentProcesses(GreengrassService component) throws IOException {
        Files.write(freezerCgroupStateFile(component.getServiceName()),
                String.valueOf(CgroupV2FreezerState.THAWED.getIndex()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void addComponentProcessToCgroup(String component, Process process)
            throws IOException {

        if (!Files.exists(CgroupV2.getSubsystemComponentPath(component))) {
            logger.atDebug().kv(COMPONENT_NAME, component)
                    .log("Resource controller is not enabled");
            return;
        }

        if (process != null) {
            try {
                Set<Integer> childProcesses = platform.getChildPids(process);
                childProcesses.add(PidUtil.getPid(process));
                Set<Integer> pidsInCgroup = pidsInComponentCgroup(component);
                if (!Utils.isEmpty(childProcesses) && Objects.nonNull(pidsInCgroup)
                        && !childProcesses.equals(pidsInCgroup)) {

                    // Writing pid to cgroup.procs file should auto add the pid to tasks file
                    // Once a process is added to a cgroup, its forked child processes inherit its (parent's) settings
                    for (Integer pid : childProcesses) {
                        if (pid == null) {
                            logger.atError().log("The process doesn't exist and is skipped");
                            continue;
                        }

                        Files.write(CgroupV2.getCgroupProcsPath(component),
                                Integer.toString(pid).getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (InterruptedException e) {
                logger.atWarn().setCause(e)
                        .log("Interrupted while getting processes to add to system limit controller");
                Thread.currentThread().interrupt();
            }
        }
    }

    private void handleErrorAddingPidToCgroup(IOException e, String component) {
        // The process might have exited (if it's a short running process).
        // Check the exception message here to avoid the exception stacktrace failing the tests.
        if (e.getMessage() != null && e.getMessage().contains("No such process")) {
            logger.atWarn().kv(COMPONENT_NAME, component)
                    .log("Failed to add pid to the cgroupv2 because the process doesn't exist anymore");
        } else {
            logger.atError().setCause(e).kv(COMPONENT_NAME, component)
                    .log("Failed to add pid to the cgroupv2");
        }
    }

    private Set<String> getMountedPaths() throws IOException {
        Set<String> mountedPaths = new HashSet<>();

        Path procMountsPath = Paths.get("/proc/self/mounts");
        List<String> mounts = Files.readAllLines(procMountsPath);
        for (String mount : mounts) {
            String[] split = mount.split(" ");
            // As reported in fstab(5) manpage, struct is:
            // 1st field is volume name
            // 2nd field is path with spaces escaped as \040
            // 3rd field is fs type
            // 4th field is mount options
            // 5th field is used by dump(8) (ignored)
            // 6th field is fsck order (ignored)
            if (split.length < 6) {
                continue;
            }

            // We only need the path of the mounts to verify whether cgroup is mounted
            String path = split[1].replace(UNICODE_SPACE, " ");
            mountedPaths.add(path);
        }
        return mountedPaths;
    }

    private void initializeCgroup(GreengrassService component) throws IOException {
        Set<String> mounts = getMountedPaths();

        if (!mounts.contains(CgroupV2.getRootPath().toString())) {
            platform.runCmd(CgroupV2.rootMountCmd(), o -> {
            }, "Failed to mount cgroup2 root");
            Utils.createPaths(CgroupV2.getSubsystemRootPath());
        }

        //Enable controllers for root group
        Files.write(CgroupV2.getRootSubTreeControlPath(),
                CGROUP_SUBTREE_CONTROL_CONTENT.getBytes(StandardCharsets.UTF_8));

        Utils.createPaths(CgroupV2.getSubsystemGGPath());
        //Enable controllers for gg group
        Files.write(CgroupV2.getGGSubTreeControlPath(),
                CGROUP_SUBTREE_CONTROL_CONTENT.getBytes(StandardCharsets.UTF_8));
        Utils.createPaths(CgroupV2.getSubsystemComponentPath(component.getServiceName()));
    }

    private Set<Integer> pidsInComponentCgroup(String component) throws IOException {
        return Files.readAllLines(CgroupV2.getCgroupProcsPath(component))
                .stream().map(Integer::parseInt).collect(Collectors.toSet());
    }

    private Path freezerCgroupStateFile(String component) {
        return CgroupV2.getCgroupFreezePath(component);
    }
}
