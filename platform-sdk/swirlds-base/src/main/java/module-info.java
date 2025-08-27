// SPDX-License-Identifier: Apache-2.0
module com.swirlds.base {
    exports com.swirlds.base;
    exports com.swirlds.base.function;
    exports com.swirlds.base.state;
    exports com.swirlds.base.time;
    exports com.swirlds.base.units;
    exports com.swirlds.base.utility;
    exports com.swirlds.base.context;
    exports com.swirlds.base.context.internal to
            com.swirlds.base.test.fixtures,
            com.swirlds.logging;
    exports com.swirlds.base.internal to
            com.swirlds.base.test.fixtures,
            com.swirlds.metrics.api,
            com.swirlds.config.api,
            com.swirlds.config.api.test.fixtures,
            com.swirlds.config.impl,
            com.swirlds.config.extensions.test.fixtures,
            com.swirlds.logging,
            com.swirlds.logging.test.fixtures,
            com.swirlds.common,
            com.swirlds.platform.base.example;
    exports com.swirlds.base.internal.observe to
            com.swirlds.base.test.fixtures,
            com.swirlds.common,
            com.swirlds.config.api,
            com.swirlds.config.api.test.fixtures,
            com.swirlds.config.extensions.test.fixtures,
            com.swirlds.config.impl,
            com.swirlds.logging,
            com.swirlds.logging.test.fixtures,
            com.swirlds.metrics.api;
    exports com.swirlds.base.time.internal to
            org.hiero.consensus.event.creator.impl; // used in TransactionPoolNexusTest

    requires static transitive com.github.spotbugs.annotations;
}
