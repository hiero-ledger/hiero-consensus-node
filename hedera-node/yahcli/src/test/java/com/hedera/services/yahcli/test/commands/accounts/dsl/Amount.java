// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.accounts.dsl;

public enum Amount {
    SHORT_OPT("-a"),
    LONG_OPT("--amount");

    private final String option;

    Amount(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
