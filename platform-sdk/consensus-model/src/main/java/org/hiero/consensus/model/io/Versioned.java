// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.io;

/**
 * A Versioned is an object that must track material changes to various implementation details using a version number.
 */
public interface Versioned {

    /**
     * Returns the version of the class implementation.
     *
     * By convention, version numbers should start at 1.
     *
     * @return version number
     */
    int getVersion();
}
