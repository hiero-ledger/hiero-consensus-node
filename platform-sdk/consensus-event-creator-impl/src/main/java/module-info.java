// SPDX-License-Identifier: Apache-2.0
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.creator.impl.DefaultEventCreator;

// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.event.creator.impl {
    exports org.hiero.consensus.event.creator.impl.rules;
    exports org.hiero.consensus.event.creator.impl.signing;
    exports org.hiero.consensus.event.creator.impl.tipset;
    exports org.hiero.consensus.event.creator.impl;

    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.consensus.event.creator;
    requires transitive org.hiero.consensus.model;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.logging;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.utility;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    provides EventCreatorModule with
            DefaultEventCreator;
}
