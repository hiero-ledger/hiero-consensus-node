// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.event.creator.test.fixtures {
    exports org.hiero.consensus.event.creator.test.fixtures;

    requires transitive com.hedera.pbj.runtime;
    requires transitive org.hiero.consensus.event.creator;
    requires static transitive com.github.spotbugs.annotations;
}
