// SPDX-License-Identifier: Apache-2.0
module org.hiero.base.crypto {
    exports org.hiero.base.crypto;
    exports org.hiero.base.crypto.config;

    /* Targeted exports */
    exports org.hiero.base.crypto.internal to
            com.swirlds.platform.core,
            com.swirlds.common.test.fixtures,
            com.swirlds.platform.core.test.fixtures,
            org.hiero.base.crypto.test.fixtures;
    exports org.hiero.base.crypto.engine to
            com.swirlds.common,
            com.swirlds.common.test.fixtures,
            org.hiero.base.crypto.test.fixtures;

    opens org.hiero.base.crypto to
            com.fasterxml.jackson.databind;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.logging;
    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.base.utility;
    requires transitive com.goterl.lazysodium;
    requires com.swirlds.base;
    requires com.sun.jna;
    requires org.apache.logging.log4j;
    requires org.bouncycastle.provider;
    requires org.hyperledger.besu.nativelib.secp256k1;
    requires static transitive com.github.spotbugs.annotations;
}
