// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.dsl;

/*
 * DSL for yahcli's `--node-account` option.
 */
public enum NodeAccount {
    SHORT_OPT("-a"),
    LONG_OPT("--node-account");

    private final String option;

    NodeAccount(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
