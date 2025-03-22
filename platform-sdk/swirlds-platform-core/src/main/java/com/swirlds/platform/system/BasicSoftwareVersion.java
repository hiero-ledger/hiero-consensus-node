// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A basic implementation of {@link SoftwareVersion} that represents a version using a long value.
 */
public class BasicSoftwareVersion implements SoftwareVersion {

    public static final long CLASS_ID = 0x777ea397b73c9830L;

    private static class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    private int softwareVersion;
    private SemanticVersion semanticVersion;

    /**
     * Zero arg constructor used for deserialization.
     */
    public BasicSoftwareVersion() {}

    /**
     * Create a new software version.
     *
     * @param softwareVersion
     * 		the version number
     */
    public BasicSoftwareVersion(final int softwareVersion) {
        this.softwareVersion = softwareVersion;
        this.semanticVersion =
                SemanticVersion.newBuilder().major(softwareVersion).build();
    }

    /**
     * Get the software version number. Distinct from {@link #getVersion()}, which returns the serialization version
     * for this object.
     *
     * @return the software version number
     */
    public int getSoftwareVersion() {
        return softwareVersion;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeLong(softwareVersion);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        // previously, softwareVersion was a long
        // since the introduction of SemanticVersion, softwareVersion was changed to an int
        // in order to avoid migration, it is still serialized as a long, but this is purely internal
        softwareVersion = Math.toIntExact(in.readLong());
        this.semanticVersion = SemanticVersion.newBuilder()
                .major(Math.toIntExact(softwareVersion))
                .build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(final SoftwareVersion that) {
        if (that == NO_VERSION) {
            // No version always comes before all other versions
            return 1;
        }

        if (that instanceof BasicSoftwareVersion thatVersion) {
            return Long.compare(softwareVersion, thatVersion.softwareVersion);
        } else {
            throw new IllegalArgumentException(
                    "Can not compare BasicSoftwareVersion to " + that.getClass().getName());
        }
    }

    // Intentionally do not implement equals() or hashCode(). Although it is legal to do so, it is not required,
    // and all platform operations should be functional without it. Since this class is used for platform testing,
    // we should make sure this crutch is not available.

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return Long.toString(softwareVersion);
    }

    @NonNull
    @Override
    public SemanticVersion getPbjSemanticVersion() {
        return semanticVersion;
    }
}
