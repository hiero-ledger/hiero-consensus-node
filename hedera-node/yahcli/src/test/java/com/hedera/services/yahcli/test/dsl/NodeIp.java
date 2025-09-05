// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.dsl;

/*
 * DSL for yahcli's `--node-ip` option.
 */
public enum NodeIp {
    SHORT_OPT("-i"),
    LONG_OPT("--node-ip");

    private final String option;

    NodeIp(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
