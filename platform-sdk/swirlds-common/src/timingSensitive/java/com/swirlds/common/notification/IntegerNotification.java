// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification;

public class IntegerNotification extends AbstractNotification {

    private int value;

    public IntegerNotification(final int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
