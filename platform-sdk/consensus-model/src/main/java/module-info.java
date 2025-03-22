// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.model {
    exports org.hiero.consensus.model.constructable;
    exports org.hiero.consensus.model.io;
    exports org.hiero.consensus.model.io.streams;
    exports org.hiero.consensus.model.platform;
    exports org.hiero.consensus.model.system.transaction;

    requires transitive com.hedera.pbj.runtime;
    requires static transitive com.github.spotbugs.annotations;
}
