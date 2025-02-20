// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable;

public interface RuntimeConstructable {
    /**
     * This method should return a random number that must be unique ID in the JVM. It should always return the same
     * number throughout the lifecycle of the class. For convenience, use the {@link GenerateClassId} class to
     * generate a random number.
     *
     * @return a unique ID for this class
     */
    long getClassId();
}
