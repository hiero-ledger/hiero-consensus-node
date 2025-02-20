// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.system;

import java.security.Permission;

@SuppressWarnings("removal")
public final class NoExitSecurityManager extends SecurityManager {
    public static final NoExitSecurityManager SINGLETON = new NoExitSecurityManager();

    private NoExitSecurityManager() {}

    @Override
    public void checkPermission(final Permission perm) {
        // allow all
    }

    @Override
    public void checkPermission(final Permission perm, final Object context) {
        // allow all
    }

    @Override
    public void checkExit(final int status) {
        throw new SystemExitException(status);
    }
}
