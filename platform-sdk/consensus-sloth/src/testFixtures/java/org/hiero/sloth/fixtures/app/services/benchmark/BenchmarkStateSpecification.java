// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.app.services.benchmark;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.sloth.fixtures.app.state.BenchmarkServiceStateSpecification;

/**
 * State specification for the benchmark service.
 *
 * <p>The benchmark service does not require any persistent state. All tracking is done
 * in memory and is not intended to survive restarts.
 */
public class BenchmarkStateSpecification implements BenchmarkServiceStateSpecification {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<StateDefinition<?, ?>> statesToCreate() {
        return Set.of();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDefaultValues(@NonNull final WritableStates states, @NonNull final SemanticVersion version) {
        // No state to initialize
    }
}
