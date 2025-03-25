// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.io.SelfSerializable;

/**
 * <p>
 * Describes the software version of a Swirlds application or platform.
 * </p>
 *
 * <p>
 * A version may be internally represented as something simple like "7", or something more complex such as "0.7.4",
 * or even something whimsical like "Rowdy Rhinoceros".
 * </p>
 *
 * <p>
 * It is highly suggested that an implementing class extend the toString() method, as that method will be used
 * to write the version into the logs.
 * </p>
 */
public interface SoftwareVersion extends SelfSerializable, Comparable<SoftwareVersion> {

    /**
     * A default version that comes before all versions. This exists for backwards compatability.
     */
    SoftwareVersion NO_VERSION = null;

    /**
     * <p>
     * Compare this version to another version. If this method returns a negative number, then this version
     * comes before that version. If this method returns a positive number, then this version comes after that
     * version. If this method returns zero, then this version is the same as that version.
     * </p>
     *
     * <p>
     * For the sake of backwards compatability, {@link #NO_VERSION} (i.e. null) should be treated as a software version
     * that comes before all other software versions. This method is expected to return a value greater than one
     * when compared to {@link #NO_VERSION}.
     * </p>
     *
     * @param that
     * 		the version to compare to. It's ok to throw an exception (e.g. {@link ClassCastException} or
     *        {@link IllegalArgumentException}) if the provided type is not the same type as this type.
     * @return a negative number if this is less than that, 0 if this is the same as that, or a positive number
     * 		if this is greater than that
     */
    @SuppressWarnings("NullableProblems")
    @Override
    int compareTo(SoftwareVersion that);

    /**
     * Returns the semantic version of the software in the form of a PBJ record that can be serialized to protobuf.
     * Since events are in the process of being converted to PBJ, this will be the only version that is used once the
     * conversion is done. Until the migration is complete, {@link SoftwareVersion} will remain in use as well.
     *
     * @return the semantic version of the software
     */
    @NonNull
    SemanticVersion getPbjSemanticVersion();
}
