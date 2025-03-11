// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This payload is logged when the browser shuts down the JVM.
 */
public class SystemExitPayload extends AbstractLogPayload {

    /**
     * The reason why the system is exiting.
     */
    private String reason;

    /**
     * THe system exit code.
     */
    private int code;

    public SystemExitPayload() {
        super("Exiting system");
    }

    public SystemExitPayload(final String reason, final int code) {
        this();
        this.reason = reason;
        this.code = code;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(final String reason) {
        this.reason = reason;
    }

    public int getCode() {
        return code;
    }

    public void setCode(final int code) {
        this.code = code;
    }
}
