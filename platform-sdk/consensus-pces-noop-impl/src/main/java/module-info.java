// SPDX-License-Identifier: Apache-2.0
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.pces.noop.impl.NoopPcesModule;

module org.hiero.consensus.pces.noop.impl {
    requires transitive org.hiero.consensus.pces;
    requires com.swirlds.component.framework;
    requires org.hiero.consensus.state;

    exports org.hiero.consensus.pces.noop.impl;

    provides PcesModule with
            NoopPcesModule;
}
