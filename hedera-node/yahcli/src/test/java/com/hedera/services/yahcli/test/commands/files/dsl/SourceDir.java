// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.files.dsl;

/*
 * DSL for sysfiles download's `--source:-dir` option.
 */
public enum SourceDir {
    SHORT_OPT("-s"),
    LONG_OPT("--source-dir");

    private final String option;

    SourceDir(final String option) {
        this.option = option;
    }

    public String str() {
        return option;
    }
}
