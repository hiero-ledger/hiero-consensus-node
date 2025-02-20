// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This payload is logged when an application calls the init() method.
 */
public class SoftwareVersionPayload extends AbstractLogPayload {

    private String trigger;
    private String previousSoftwareVersion;

    /**
     * Zero arg constructor, required by log payload framework.
     */
    public SoftwareVersionPayload() {}

    /**
     * @param message
     * 		a human readable message
     * @param trigger
     * 		describes the reason why the state was created/recreated
     * @param previousSoftwareVersion
     * 		the previous version of the software, as a String.
     */
    public SoftwareVersionPayload(final String message, final String trigger, final String previousSoftwareVersion) {
        super(message);
        this.trigger = trigger;
        this.previousSoftwareVersion = previousSoftwareVersion;
    }

    public String getTrigger() {
        return trigger;
    }

    public void setTrigger(final String trigger) {
        this.trigger = trigger;
    }

    public String getPreviousSoftwareVersion() {
        return previousSoftwareVersion;
    }

    public void setPreviousSoftwareVersion(final String previousSoftwareVersion) {
        this.previousSoftwareVersion = previousSoftwareVersion;
    }
}
