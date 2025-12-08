// SPDX-License-Identifier: Apache-2.0
/**
 * A map that implements the FastCopyable interface.
 */
open module com.swirlds.merkle {
    exports com.swirlds.merkle.tree;
    exports com.swirlds.merkle.tree.internal to
            com.swirlds.merkle.test.fixtures;

    requires transitive com.swirlds.common;
    requires org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires static transitive com.github.spotbugs.annotations;
}
