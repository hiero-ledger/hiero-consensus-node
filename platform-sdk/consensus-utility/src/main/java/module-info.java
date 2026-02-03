// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.config.UtilityConfigurationExtension;

module org.hiero.consensus.utility {
    exports org.hiero.consensus.monitoring;
    exports org.hiero.consensus.config;
    exports org.hiero.consensus.crypto;
    exports org.hiero.consensus.event;
    exports org.hiero.consensus.exceptions;
    exports org.hiero.consensus.io;
    exports org.hiero.consensus.io.extendable;
    exports org.hiero.consensus.io.extendable.extensions;
    exports org.hiero.consensus.node;
    exports org.hiero.consensus.orphan;
    exports org.hiero.consensus.transaction;
    exports org.hiero.consensus.round;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.consensus.model;
    requires com.swirlds.logging;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.metrics;
    requires com.goterl.lazysodium;
    requires org.apache.logging.log4j;
    requires org.bouncycastle.pkix;
    requires org.bouncycastle.provider;
    requires static transitive com.github.spotbugs.annotations;

    provides ConfigurationExtension with
            UtilityConfigurationExtension;
}
