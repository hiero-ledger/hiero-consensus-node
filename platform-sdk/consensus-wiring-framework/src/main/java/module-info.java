// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.wiring.framework {
    exports org.hiero.consensus.wiring.framework.component;
    exports org.hiero.consensus.wiring.framework.counters;
    exports org.hiero.consensus.wiring.framework.model.diagram;
    exports org.hiero.consensus.wiring.framework.model;
    exports org.hiero.consensus.wiring.framework.schedulers.builders;
    exports org.hiero.consensus.wiring.framework.schedulers.internal;
    exports org.hiero.consensus.wiring.framework.schedulers;
    exports org.hiero.consensus.wiring.framework.transformers;
    exports org.hiero.consensus.wiring.framework.wires.input;
    exports org.hiero.consensus.wiring.framework.wires.output;
    exports org.hiero.consensus.wiring.framework.wires;
    exports org.hiero.consensus.wiring.framework;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.consensus.metrics;
    requires com.swirlds.logging;
    requires org.hiero.base.concurrent;
    requires org.hiero.base.utility;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
