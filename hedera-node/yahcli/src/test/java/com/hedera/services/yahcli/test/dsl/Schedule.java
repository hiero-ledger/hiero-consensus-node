// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.dsl;

/*
 * DSL for yahcli's `--schedule` option.
 */
public enum Schedule {
    SHORT_OPT("-s"),
    LONG_OPT("--schedule");

    private final String option;

    Schedule(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
