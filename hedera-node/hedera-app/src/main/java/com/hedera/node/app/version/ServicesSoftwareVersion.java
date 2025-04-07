// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.version;

import static com.swirlds.state.lifecycle.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.HapiUtils.deserializeSemVer;
import static com.swirlds.state.lifecycle.HapiUtils.serializeSemVer;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.VersionConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.system.SoftwareVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * An implementation of {@link SoftwareVersion} which consolidates the production {@link SemanticVersion}
 * derived from the <i>semantic-version.properties</i> resource and the dev-only {@code hedera.config.version}
 * property into a single object by ignoring the {@code build} part of whatever semantic version is given
 * and replacing it with the {@code configVersion} if it is non-zero.
 */
public final class ServicesSoftwareVersion implements SoftwareVersion {
    private static final int VERSION = 1;
    private static final long CLASS_ID = 0x6f2b1bc2df8cbd0dL;

    private SemanticVersion stateSemVer;

    /**
     * Constructs a {@link ServicesSoftwareVersion} for immediate deserialization.
     */
    public ServicesSoftwareVersion() {
        // RuntimeConstructable
    }

    /**
     * Constructs a {@link ServicesSoftwareVersion} from the given {@link SemanticVersion} that
     * has already incorporated the {@code hedera.config.version} property.
     * @param stateSemVer the semantic version
     */
    public ServicesSoftwareVersion(@NonNull final SemanticVersion stateSemVer) {
        this.stateSemVer = requireNonNull(stateSemVer);
    }

    /**
     * Constructor used when creating the current version from <i>semantic-version.properties</i> resource
     * and the {@code hedera.config.version} property.
     * @param semVer the semantic version from the <i>semantic-version.properties</i> resource
     * @param configVersion the numeric config version
     */
    public ServicesSoftwareVersion(@NonNull final SemanticVersion semVer, final int configVersion) {
        requireNonNull(semVer);
        // We must preserve the original semantic version in state so that reconnected nodes
        // will not believe they are upgrading when they restart with a non-zero config version
        this.stateSemVer = semVer.copyBuilder().build("" + configVersion).build();
    }

    /**
     * Returns the software version set in the given configuration.
     * @param config the configuration to examine
     * @return the software version
     */
    public static ServicesSoftwareVersion from(@NonNull final Configuration config) {
        final var versionConfig = config.getConfigData(VersionConfig.class);
        return new ServicesSoftwareVersion(
                versionConfig.servicesVersion(),
                config.getConfigData(HederaConfig.class).configVersion());
    }

    @Override
    public int compareTo(@Nullable final SoftwareVersion other) {
        if (other == null) {
            return 1;
        } else if (other instanceof ServicesSoftwareVersion that) {
            return SEMANTIC_VERSION_COMPARATOR.compare(this.stateSemVer, that.stateSemVer);
        } else {
            throw new IllegalStateException(
                    "Unknown SoftwareVersion type: " + other.getClass().getName());
        }
    }

    @Override
    public @NonNull SemanticVersion getPbjSemanticVersion() {
        return stateSemVer;
    }

    @Override
    public void deserialize(@NonNull final SerializableDataInputStream in, final int version) throws IOException {
        requireNonNull(in);
        stateSemVer = deserializeSemVer(in);
    }

    @Override
    public void serialize(@NonNull final SerializableDataOutputStream out) throws IOException {
        requireNonNull(out);
        serializeSemVer(stateSemVer, out);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public String toString() {
        return stateSemVer.toString();
    }
}
