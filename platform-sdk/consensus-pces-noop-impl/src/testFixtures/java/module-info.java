// SPDX-License-Identifier: Apache-2.0
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.pces.noop.impl.test.fixtures.NoopPcesModule;

open module org.hiero.consensus.pces.noop.impl.test.fixtures {
    exports org.hiero.consensus.pces.noop.impl.test.fixtures;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.metrics;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.pces;
    requires transitive org.hiero.consensus.state;
    requires transitive org.hiero.consensus.utility;
    requires static transitive com.github.spotbugs.annotations;

    provides PcesModule with
            NoopPcesModule;
}
