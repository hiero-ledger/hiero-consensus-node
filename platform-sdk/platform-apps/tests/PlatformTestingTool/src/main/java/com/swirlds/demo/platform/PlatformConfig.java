// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

public class PlatformConfig {
    /** should this run with no windows? */
    boolean headless = true;

    private PlatformConfig() {}

    static PlatformConfig getDefault() {
        return new PlatformConfig();
    }
}
