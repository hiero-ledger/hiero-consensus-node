// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.io;

/**
 * An object implementing this interface can return its length even before serialization.
 *
 * Different instances of the same class could have different lengths due to different
 * internal values, i.e, different length of an array variable.
 */
public interface SerializableWithKnownLength extends SelfSerializable {
    /** get an object's serialized length without doing serialization first */
    int getSerializedLength();
}
