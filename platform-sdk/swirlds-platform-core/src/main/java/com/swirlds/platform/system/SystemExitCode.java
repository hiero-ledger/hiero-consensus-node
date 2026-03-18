// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

public enum SystemExitCode {
    NO_ERROR(0),
    /**
     * Configuration error.
     */
    CONFIGURATION_ERROR(33),
    /**
     * This node encountered an ISS.
     */
    ISS(199),
    SAVED_STATE_NOT_LOADED(200),
    SWIRLD_MAIN_THREW_EXCEPTION(201),
    /**
     * This node has fallen behind but can not reconnect due to policy.
     */
    BEHIND_RECONNECT_DISABLED(202),
    /**
     * This node exceeded the maximum consecutive failed reconnect attempts.
     */
    RECONNECT_FAILURE(203),
    /**
     * An issue occurred while loading keys from .pfx files
     */
    KEY_LOADING_FAILED(204),
    /**
     * The ID of the self node was not provided.
     */
    NODE_ID_NOT_PROVIDED(205),
    /**
     * An exit was called but no code was supplied
     */
    NO_EXIT_CODE(254),
    /**
     * An unrecoverable error.
     */
    FATAL_ERROR(255);

    private final int exitCode;

    SystemExitCode(final int exitCode) {
        this.exitCode = exitCode;
    }

    public int getExitCode() {
        return exitCode;
    }

    public boolean isError() {
        return exitCode != 0;
    }
}
