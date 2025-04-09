// SPDX-License-Identifier: Apache-2.0
/**
 * A HashMap-like structure that implements the FastCopyable interface.
 */
module com.swirlds.fchashmap {
    requires transitive com.swirlds.common;
    requires transitive org.hiero.base.utility;
    requires static transitive com.github.spotbugs.annotations;

    exports com.swirlds.fchashmap;
}
