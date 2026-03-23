// SPDX-License-Identifier: Apache-2.0
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.pces.noop.impl.test.fixtures.NoopPcesModule;

open module org.hiero.consensus.pces.noop.impl.test.fixtures {
    exports org.hiero.consensus.pces.noop.impl.test.fixtures;

    requires transitive org.hiero.consensus.pces;
    requires com.swirlds.component.framework;
    requires org.hiero.consensus.state;
    requires static transitive com.github.spotbugs.annotations;

    provides PcesModule with
            NoopPcesModule;
}
