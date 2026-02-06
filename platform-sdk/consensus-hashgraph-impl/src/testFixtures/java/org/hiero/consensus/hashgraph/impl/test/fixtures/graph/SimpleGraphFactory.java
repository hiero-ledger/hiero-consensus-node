// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.graph;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.event.PlatformEvent;

@FunctionalInterface
public interface SimpleGraphFactory<T> {

    SimpleGraph<T> createSimpleGraph(@NonNull Random random, @NonNull List<PlatformEvent> events);
}
