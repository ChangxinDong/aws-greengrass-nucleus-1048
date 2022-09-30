/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Represents Linux cgroup v2.
 */
@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME", justification = "Cgroup virtual filesystem path "
        + "cannot be relative")
public final class CgroupV2 {

    private static final String CGROUP_ROOT = "/sys/fs/cgroup";
    private static final String GG_NAMESPACE = "greengrass";
    private static final String CPU_MAX = "cpu.max";
    private static final String MEMORY_MAX = "memory.max";
    private static final String CGROUP_PROCS = "cgroup.procs";
    private static final String CGROUP_SUBTREE_CONTROL = "cgroup.subtree_control";
    private static final String CGROUP_FREEZE = "cgroup.freeze";

    private CgroupV2() {
    }

    public static Path getRootPath() {
        return Paths.get(CGROUP_ROOT);
    }

    public static String rootMountCmd() {
        return String.format("mount -t cgroup2 none %s", CGROUP_ROOT);
    }

    public static Path getSubsystemRootPath() {
        return Paths.get(CGROUP_ROOT);
    }

    public static Path getRootSubTreeControlPath() {
        return getSubsystemRootPath().resolve(CGROUP_SUBTREE_CONTROL);
    }

    public static Path getSubsystemGGPath() {
        return getSubsystemRootPath().resolve(GG_NAMESPACE);
    }

    public static Path getGGSubTreeControlPath() {
        return getSubsystemGGPath().resolve(CGROUP_SUBTREE_CONTROL);
    }

    public static Path getSubsystemComponentPath(String componentName) {
        return getSubsystemGGPath().resolve(componentName);
    }

    public static Path getComponentCpuMaxPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CPU_MAX);
    }

    public static Path getComponentMemoryMaxPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(MEMORY_MAX);
    }

    public static Path getCgroupProcsPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CGROUP_PROCS);
    }

    public static Path getCgroupFreezePath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CGROUP_FREEZE);
    }
}
