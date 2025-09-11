// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.accounts.dsl;

/*
 * DSL for accounts command's `--inside-batch` option.
 */
public enum InsideBatch {
    LONG_OPT("--inside-batch");

    private final String option;

    InsideBatch(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
