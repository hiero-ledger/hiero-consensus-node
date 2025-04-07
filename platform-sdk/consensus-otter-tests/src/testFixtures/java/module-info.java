// SPDX-License-Identifier: Apache-2.0
module org.hiero.otter.fixtures {
    requires transitive com.swirlds.logging;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.base.test.fixtures;
    requires com.swirlds.common.test.fixtures;
    requires com.swirlds.logging;
    requires com.swirlds.platform.core.test.fixtures;
    requires org.apache.logging.log4j;
    requires org.hiero.consensus.model;

    exports org.hiero.otter.fixtures;
}
