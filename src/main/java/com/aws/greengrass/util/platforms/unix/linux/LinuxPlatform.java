/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import com.aws.greengrass.util.platforms.SystemResourceController;
import com.aws.greengrass.util.platforms.unix.UnixPlatform;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
        justification = "Cgroup Controller virtual filesystem path cannot be relative")
public class LinuxPlatform extends UnixPlatform {
    private static final Path CGROUP_CONTROLLERS = Paths.get("/sys/fs/cgroup/cgroup.controllers");

    SystemResourceController systemResourceController;

    @Override
    public SystemResourceController getSystemResourceController() {
        //if the path exists, identify it as cgroupv1, otherwise identify it as cgroupv2
        if (Files.exists(CGROUP_CONTROLLERS)) {
            systemResourceController = new LinuxSystemResourceControllerV2(this);
        } else {
            systemResourceController = new LinuxSystemResourceController(this);
        }

        return systemResourceController;
    }

}
