// SPDX-License-Identifier: Apache-2.0
module com.swirlds.component.framework {
    exports com.swirlds.component.framework;
    exports com.swirlds.component.framework.model.diagram;
    exports com.swirlds.component.framework.component;
    exports com.swirlds.component.framework.counters;
    exports com.swirlds.component.framework.model;
    exports com.swirlds.component.framework.schedulers;
    exports com.swirlds.component.framework.schedulers.builders;
    exports com.swirlds.component.framework.schedulers.internal;
    exports com.swirlds.component.framework.transformers;
    exports com.swirlds.component.framework.wires;
    exports com.swirlds.component.framework.wires.input;
    exports com.swirlds.component.framework.wires.output;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires com.swirlds.logging;
    requires org.hiero.base.utility;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
