// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.bdd;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.yahcli.test.YahcliVerbs;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.Optional;

public abstract class AbstractYahcliOperation<T extends AbstractYahcliOperation<T>> implements SpecOperation {
    @Nullable
    protected String configLoc;

    @Nullable
    protected String workingDir;

    protected abstract T self();

    public T withConfigLoc(@NonNull final String configLoc) {
        this.configLoc = requireNonNull(configLoc);
        return self();
    }

    public T withWorkingDir(@NonNull final String workingDir) {
        this.workingDir = requireNonNull(workingDir);
        return self();
    }

    protected String workingDirOrThrow() {
        return Optional.ofNullable(workingDir)
                .orElseGet(() -> Objects.requireNonNull(
                        YahcliVerbs.DEFAULT_WORKING_DIR.get(),
                        "No default working dir set, and none provided via args"));
    }

    protected String configLocOrThrow() {
        return Optional.ofNullable(configLoc)
                .orElseGet(() -> requireNonNull(
                        YahcliVerbs.DEFAULT_CONFIG_LOC.get(),
                        "No default config location set, and none provided via args"));
    }
}
