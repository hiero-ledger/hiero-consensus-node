// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.restart;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.fixtures.state.FakeState;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A no-op implementation of a {@link SavedStateSpec}.
 */
public class NoopSavedStateSpec implements SavedStateSpec {
    @Override
    public void accept(@NonNull final FakeState state) {
        requireNonNull(state);
    }
}
