// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.files.dsl;

/*
 * DSL for sysfiles download's `--dest-dir` option.
 */
public enum DestDir {
    SHORT_OPT("-d"),
    LONG_OPT("--dest-dir");

    private final String option;

    DestDir(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
