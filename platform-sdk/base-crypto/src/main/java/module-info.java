// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.base.crypto.config.CryptoConfigurationExtension;

module org.hiero.base.crypto {
    exports org.hiero.base.crypto;
    exports org.hiero.base.crypto.config;

    /* Targeted exports */
    exports org.hiero.base.crypto.engine to
            com.swirlds.common,
            com.swirlds.common.test.fixtures,
            org.hiero.base.crypto.test.fixtures;

    opens org.hiero.base.crypto to
            com.swirlds.platform.core,
            com.swirlds.common.test.fixtures,
            com.swirlds.platform.core.test.fixtures,
            org.hiero.base.crypto.test.fixtures,
            org.hiero.consensus.utility,
            org.hiero.otter.test,
            com.fasterxml.jackson.databind;

    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.logging;
    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.base.utility;
    requires transitive com.goterl.lazysodium;
    requires transitive org.apache.logging.log4j;
    requires com.swirlds.base;
    requires com.sun.jna;
    requires org.bouncycastle.provider;
    requires org.hyperledger.besu.nativelib.secp256k1;
    requires static transitive com.github.spotbugs.annotations;

    provides ConfigurationExtension with
            CryptoConfigurationExtension;
}
