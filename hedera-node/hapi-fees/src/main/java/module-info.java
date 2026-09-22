// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.app.hapi.fees {
    exports org.hiero.hapi.fees;

    requires transitive com.hedera.node.hapi;
    requires com.hedera.node.app.hapi.utils;
    requires com.hedera.pbj.runtime;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
