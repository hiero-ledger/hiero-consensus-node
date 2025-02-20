// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.system;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.platform.system.SystemExitCode;
import com.swirlds.platform.system.SystemExitUtils;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class SystemUtilsTest {
    @Test
    @Disabled("Not longer supported in Java 21")
    void checkDefaultCharsetTest() {
        // System.setSecurityManager() is deprecated, but they do intend on providing an alternative in future release
        // for blocking System.exit(). Mentioned here: https://openjdk.java.net/jeps/411

        // set up the security manager to throw an exception when System.exit is called
        System.setSecurityManager(NoExitSecurityManager.SINGLETON);

        // test with non error reason
        assertThrows(
                SystemExitException.class,
                () -> SystemExitUtils.exitSystem(SystemExitCode.NO_ERROR),
                "the method should call System.exit and throw");
        // test with error reason
        assertThrows(
                SystemExitException.class,
                () -> SystemExitUtils.exitSystem(SystemExitCode.SAVED_STATE_NOT_LOADED),
                "the method should call System.exit and throw");

        // reset the security manager
        System.setSecurityManager(null);
    }
}
