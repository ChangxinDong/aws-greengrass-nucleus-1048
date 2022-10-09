/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import com.aws.greengrass.util.platforms.SystemResourceController;
import com.aws.greengrass.util.platforms.unix.UnixPlatform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class LinuxPlatform extends UnixPlatform {
    private static final String CGROUP_ROOT = "/sys/fs/cgroup";
    private static final String CGROUP_CONTROLLERS = "cgroup.controllers";

    SystemResourceController systemResourceController;

    @Override
    public SystemResourceController getSystemResourceController() {
        //if the path exists, identify it as cgroupv1, otherwise identify it as cgroupv2
        if (Files.exists(getControllersRootPath())) {
            systemResourceController = new LinuxSystemResourceControllerV2(this);
        } else {
            systemResourceController = new LinuxSystemResourceController(this);
        }

        return systemResourceController;
    }

    private Path getControllersRootPath() {
        return Paths.get(CGROUP_ROOT).resolve(CGROUP_CONTROLLERS);
    }
}
