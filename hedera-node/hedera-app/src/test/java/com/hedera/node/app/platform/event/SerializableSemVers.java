// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.platform.event;

import com.google.common.annotations.VisibleForTesting;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import com.swirlds.platform.system.SoftwareVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Pattern;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class SerializableSemVers implements SoftwareVersion {
    private static final String IS_INCOMPARABLE_MSG = " cannot be compared to ";
    private static final Pattern ALPHA_PRE_PATTERN = Pattern.compile("alpha[.](\\d+)");
    private static boolean currentVersionHasPatchMigrationRecords = false;
    public static final int RELEASE_027_VERSION = 1;
    public static final long CLASS_ID = 0x6f2b1bc2df8cbd0bL;

    private int servicesPreAlphaNumber;
    private int protoPreAlphaNumber;
    private String servicesBuild;
    private String protoBuild;
    private SemanticVersion proto;
    private SemanticVersion services;

    // Just compares major minor and patch versions. Pre and Build are compared in FULL_COMPARATOR.
    private static final Comparator<SemanticVersion> SEM_VER_COMPARATOR = Comparator.comparingInt(
                    SemanticVersion::getMajor)
            .thenComparingInt(SemanticVersion::getMinor)
            .thenComparingInt(SemanticVersion::getPatch);

    // Whenever there is a need for doing an upgrade with config-only changes,
    // we set the build portion on semver. This is needed to trigger platform
    // upgrade code, which is otherwise not triggered for config-only changes.
    static final Comparator<SerializableSemVers> FULL_COMPARATOR = Comparator.comparing(
                    SerializableSemVers::getServices, SEM_VER_COMPARATOR)
            .thenComparingInt(SerializableSemVers::getServicesPreAlphaNumber)
            .thenComparing(SerializableSemVers::getServicesBuild)
            .thenComparing(SerializableSemVers::getProto, SEM_VER_COMPARATOR)
            .thenComparingInt(SerializableSemVers::getProtoPreAlphaNumber)
            .thenComparing(SerializableSemVers::getProtoBuild);

    public SerializableSemVers() {
        // RuntimeConstructable
    }

    public SerializableSemVers(@NonNull final SemanticVersion proto, @NonNull final SemanticVersion services) {
        this.proto = proto;
        this.services = services;

        setServicesPreAlphaNumberAndBuild();
        setProtoPreAlphaNumberAndBuild();
    }

    public static SerializableSemVers forHapiAndHedera(@NonNull final String proto, @NonNull final String services) {
        return new SerializableSemVers(asSemVer(proto), asSemVer(services));
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return RELEASE_027_VERSION;
    }

    @Override
    public int getMinimumSupportedVersion() {
        return RELEASE_027_VERSION;
    }

    // The software version can be null here because we use deserializedVersion as null
    // on genesis initialization.
    public boolean isAfter(@Nullable final SoftwareVersion other) {
        return compareTo(other) > 0;
    }

    // The software version can be null here because we use deserializedVersion as null
    // on genesis initialization.
    public boolean isBefore(@Nullable final SoftwareVersion other) {
        return compareTo(other) < 0;
    }

    // The software version can be null here because we use deserializedVersion as null
    // on genesis initialization.
    public boolean hasMigrationRecordsFrom(@Nullable final SoftwareVersion other) {
        return isNonPatchUpgradeFrom(other) || (this.isAfter(other) && currentVersionHasPatchMigrationRecords);
    }

    public boolean isNonConfigUpgrade(@Nullable final SoftwareVersion other) {
        // The software version can be null here because we use deserializedVersion as null
        // on genesis initialization.
        if (other == null) {
            return true;
        }
        if (other instanceof SerializableSemVers that) {
            return this.isAfter(that) && haveDifferentNonBuildVersions(this, that);
        } else {
            throw new IllegalArgumentException("Version " + this + IS_INCOMPARABLE_MSG + other);
        }
    }

    @VisibleForTesting
    boolean isNonPatchUpgradeFrom(@Nullable final SoftwareVersion other) {
        // The software version can be null here because we use deserializedVersion as null
        // on genesis initialization.
        if (other == null) {
            return true;
        }
        if (other instanceof SerializableSemVers that) {
            return this.isAfter(that) && haveDifferentMajorAndMinorVersions(this, that);
        } else {
            throw new IllegalArgumentException("Version " + this + IS_INCOMPARABLE_MSG + other);
        }
    }

    private boolean haveDifferentMajorAndMinorVersions(
            @NonNull final SerializableSemVers a, @NonNull final SerializableSemVers b) {
        return a.services.getMajor() != b.services.getMajor() || a.services.getMinor() != b.services.getMinor();
    }

    private boolean haveDifferentNonBuildVersions(
            @NonNull final SerializableSemVers a, @NonNull final SerializableSemVers b) {
        return haveDifferentMajorAndMinorVersions(a, b)
                || a.services.getPatch() != b.services.getPatch()
                || !a.services.getPre().equals(b.services.getPre());
    }

    @Override
    public int compareTo(@Nullable final SoftwareVersion other) {
        if (proto == null || services == null) {
            throw new IllegalStateException("Uninitialized version " + this + IS_INCOMPARABLE_MSG + other);
        }
        if (other == SoftwareVersion.NO_VERSION) {
            // We are later than any unknown version
            return 1;
        }
        if (other instanceof SerializableSemVers that) {
            return FULL_COMPARATOR.compare(this, that);
        } else {
            return -1;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(proto, services);
    }

    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        proto = deserializeSemVer(in);
        services = deserializeSemVer(in);

        setServicesPreAlphaNumberAndBuild();
        setProtoPreAlphaNumberAndBuild();
    }

    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        serializeSemVer(proto, out);
        serializeSemVer(services, out);
    }

    @Override
    public String toString() {
        return "Services @ " + readable(services) + " | HAPI @ " + readable(proto);
    }

    private static void serializeSemVer(final SemanticVersion semVer, final SerializableDataOutputStream out)
            throws IOException {
        out.writeInt(semVer.getMajor());
        out.writeInt(semVer.getMinor());
        out.writeInt(semVer.getPatch());
        serializeIfUsed(semVer.getPre(), out);
        serializeIfUsed(semVer.getBuild(), out);
    }

    private static void serializeIfUsed(final String semVerPart, final SerializableDataOutputStream out)
            throws IOException {
        if (semVerPart.isBlank()) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeNormalisedString(semVerPart);
        }
    }

    private static SemanticVersion deserializeSemVer(final SerializableDataInputStream in) throws IOException {
        final var ans = SemanticVersion.newBuilder();
        ans.setMajor(in.readInt()).setMinor(in.readInt()).setPatch(in.readInt());
        if (in.readBoolean()) {
            ans.setPre(in.readNormalisedString(Integer.MAX_VALUE));
        }
        if (in.readBoolean()) {
            ans.setBuild(in.readNormalisedString(Integer.MAX_VALUE));
        }
        return ans.build();
    }

    private static String readable(@Nullable final SemanticVersion semVer) {
        if (semVer == null) {
            return "<N/A>";
        }
        final var sb = new StringBuilder()
                .append(semVer.getMajor())
                .append(".")
                .append(semVer.getMinor())
                .append(".")
                .append(semVer.getPatch());
        if (!semVer.getPre().isBlank()) {
            sb.append("-").append(semVer.getPre());
        }
        if (!semVer.getBuild().isBlank()) {
            sb.append("+").append(semVer.getBuild());
        }
        return sb.toString();
    }

    private int alphaNumberOf(@NonNull final String pre) {
        final var alphaMatch = ALPHA_PRE_PATTERN.matcher(pre);
        // alpha versions come before everything else
        return alphaMatch.matches() ? Integer.parseInt(alphaMatch.group(1)) : Integer.MAX_VALUE;
    }

    private void setServicesPreAlphaNumberAndBuild() {
        servicesPreAlphaNumber = alphaNumberOf(services.getPre());
        servicesBuild = services.getBuild();
    }

    private void setProtoPreAlphaNumberAndBuild() {
        protoPreAlphaNumber = alphaNumberOf(proto.getPre());
        protoBuild = proto.getBuild();
    }

    public SemanticVersion getProto() {
        return proto;
    }

    public SemanticVersion getServices() {
        return services;
    }

    public int getServicesPreAlphaNumber() {
        return servicesPreAlphaNumber;
    }

    public int getProtoPreAlphaNumber() {
        return protoPreAlphaNumber;
    }

    public String getServicesBuild() {
        return servicesBuild;
    }

    public String getProtoBuild() {
        return protoBuild;
    }

    @VisibleForTesting
    public void setProto(final SemanticVersion proto) {
        this.proto = proto;
    }

    @VisibleForTesting
    public void setServices(final SemanticVersion services) {
        this.services = services;
    }

    @VisibleForTesting
    static void setCurrentVersionHasPatchMigrationRecords(final boolean currentVersionHasPatchMigrationRecords) {
        SerializableSemVers.currentVersionHasPatchMigrationRecords = currentVersionHasPatchMigrationRecords;
    }

    @Override
    @NonNull
    public com.hedera.hapi.node.base.SemanticVersion getPbjSemanticVersion() {
        return new com.hedera.hapi.node.base.SemanticVersion(
                services.getMajor(), services.getMinor(), services.getPatch(), services.getPre(), services.getBuild());
    }

    /* From https://semver.org/#is-there-a-suggested-regular-expression-regex-to-check-a-semver-string */
    private static final Pattern SEMVER_SPEC_REGEX = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)"
                    + "(?:\\."
                    + "(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)"
                    + "*))?$");

    private static SemanticVersion asSemVer(final String value) {
        final var matcher = SEMVER_SPEC_REGEX.matcher(value);
        if (matcher.matches()) {
            final var builder = SemanticVersion.newBuilder()
                    .setMajor(Integer.parseInt(matcher.group(1)))
                    .setMinor(Integer.parseInt(matcher.group(2)))
                    .setPatch(Integer.parseInt(matcher.group(3)));
            if (matcher.group(4) != null) {
                builder.setPre(matcher.group(4));
            }
            if (matcher.group(5) != null) {
                builder.setBuild(matcher.group(5));
            }
            return builder.build();
        } else {
            throw new IllegalArgumentException("Argument value='" + value + "' is not a valid semver");
        }
    }
}
