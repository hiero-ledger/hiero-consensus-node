// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.dsl;

/*
 * DSL for yahcli's `--config` option.
 */
public enum Config {
    SHORT_OPT("-c"),
    LONG_OPT("--config");

    private final String option;

    Config(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
