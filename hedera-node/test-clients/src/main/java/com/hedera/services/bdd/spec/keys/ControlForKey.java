// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.keys;

public class ControlForKey {
    private final String keyName;
    private final SigControl controller;

    public String getKeyName() {
        return keyName;
    }

    public SigControl getController() {
        return controller;
    }

    public static ControlForKey forKey(String key, SigControl control) {
        return new ControlForKey(key, control);
    }

    private ControlForKey(String keyName, SigControl controller) {
        this.keyName = keyName;
        this.controller = controller;
    }
}
