// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.dsl;

/*
 * DSL for yahcli's `--verbose` option.
 */
public enum LogLevel {
    SHORT_OPT("-v"),
    LONG_OPT("--verbose");

    private final String option;

    LogLevel(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
