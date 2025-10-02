// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashSet;
import java.util.Set;

/**
 * An implementation of {@link Schema} that takes an integer as the version and has no
 * implementation for the various methods. Test code can subclass this to add the behavior they'd
 * like to test with.
 */
public class TestSchema extends Schema<SemanticVersion> {

    public static final SemanticVersion CURRENT_VERSION = new SemanticVersion(0, 47, 0, "SNAPSHOT", "");
    private final Runnable onMigrate;
    private final Runnable onRestart;
    private final Set<StateDefinition> statesToCreate;
    private final Set<Integer> statesToRemove;

    public TestSchema(int version) {
        this(SemanticVersion.newBuilder().major(version).build());
    }

    public TestSchema(int major, int minor, int patch) {
        this(SemanticVersion.newBuilder().major(major).minor(minor).patch(patch).build());
    }

    public TestSchema(SemanticVersion version) {
        this(version, null);
    }

    public TestSchema(SemanticVersion version, Runnable onMigrate) {
        this(version, Set.of(), Set.of(), onMigrate, null);
    }

    public TestSchema(
            @NonNull final SemanticVersion version,
            @NonNull final Set<StateDefinition> statesToCreate,
            @NonNull final Set<Integer> statesToRemove,
            @Nullable Runnable onMigrate,
            @Nullable Runnable onRestart) {
        super(version, SEMANTIC_VERSION_COMPARATOR);
        this.onMigrate = onMigrate;
        this.onRestart = onRestart;
        this.statesToCreate = statesToCreate;
        this.statesToRemove = statesToRemove;
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        super.migrate(ctx);
        if (onMigrate != null) {
            onMigrate.run();
        }
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return statesToCreate;
    }

    @NonNull
    @Override
    public Set<Integer> statesToRemove() {
        return statesToRemove;
    }

    @Override
    public void restart(@NonNull MigrationContext ctx) {
        super.restart(ctx);
        if (onRestart != null) {
            onRestart.run();
        }
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {

        private SemanticVersion version = CURRENT_VERSION;
        private Runnable onMigrate;
        private Runnable onRestart;
        private final Set<StateDefinition> statesToCreate = new HashSet<>();
        private final Set<Integer> statesToRemove = new HashSet<>();

        public Builder version(SemanticVersion version) {
            this.version = version;
            return this;
        }

        public Builder majorVersion(int major) {
            this.version = version.copyBuilder().major(major).build();
            return this;
        }

        public Builder minorVersion(int minor) {
            this.version = version.copyBuilder().minor(minor).build();
            return this;
        }

        public Builder patchVersion(int patch) {
            this.version = version.copyBuilder().patch(patch).build();
            return this;
        }

        public Builder onMigrate(Runnable onMigrate) {
            this.onMigrate = onMigrate;
            return this;
        }

        public Builder onRestart(Runnable onRestart) {
            this.onRestart = onRestart;
            return this;
        }

        public Builder stateToCreate(StateDefinition state) {
            this.statesToCreate.add(state);
            return this;
        }

        public Builder stateToRemove(int state) {
            this.statesToRemove.add(state);
            return this;
        }

        public TestSchema build() {
            return new TestSchema(version, statesToCreate, statesToRemove, onMigrate, onRestart);
        }
    }
}
