// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.constructable;

public interface RuntimeConstructable {
    /**
     * This method should return a random number that must be unique ID in the JVM. It should always return the same
     * number throughout the lifecycle of the class. For convenience, use the
     * {@code com.swirlds.common.constructable.GenerateClassId} class to generate a random number.
     *
     * @return a unique ID for this class
     */
    long getClassId();
}
