// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.model {
    requires transitive com.hedera.pbj.runtime;

    exports org.hiero.consensus.model.constructable;
    exports org.hiero.consensus.model.io;
    exports org.hiero.consensus.model.system.transaction;

    requires static transitive com.github.spotbugs.annotations;
}
