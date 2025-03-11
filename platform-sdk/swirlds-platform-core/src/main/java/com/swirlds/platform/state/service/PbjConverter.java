// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.crypto.SerializableX509Certificate;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.platform.state.PlatformStateModifier;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.system.address.AddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * This class handles conversion from PBJ objects related to the platform state to the corresponding Java objects, and vice versa.
 */
public final class PbjConverter {
    /**
     * Converts an instance of {@link PlatformStateModifier} to PBJ representation (an instance of {@link com.hedera.hapi.platform.state.PlatformState}.)
     * @param accessor the source of the data
     * @return the platform state as PBJ object
     */
    @NonNull
    public static com.hedera.hapi.platform.state.PlatformState toPbjPlatformState(
            @NonNull final PlatformStateAccessor accessor) {
        requireNonNull(accessor);
        return new PlatformState(
                accessor.getCreationSoftwareVersion().getPbjSemanticVersion(),
                accessor.getRoundsNonAncient(),
                accessor.getSnapshot(),
                toPbjTimestamp(accessor.getFreezeTime()),
                toPbjTimestamp(accessor.getLastFrozenTime()),
                Optional.ofNullable(accessor.getLegacyRunningEventHash())
                        .map(Hash::getBytes)
                        .orElse(null),
                accessor.getLowestJudgeGenerationBeforeBirthRoundMode(),
                accessor.getLastRoundBeforeBirthRoundMode(),
                Optional.ofNullable(accessor.getFirstVersionInBirthRoundMode())
                        .map(SoftwareVersion::getPbjSemanticVersion)
                        .orElse(null));
    }

    /**
     * Converts an instance of {@link PlatformStateModifier} to PBJ representation (an instance of {@link com.hedera.hapi.platform.state.PlatformState}.)
     * @param accumulator the source of the data
     * @return the platform state as PBJ object
     */
    @NonNull
    public static com.hedera.hapi.platform.state.PlatformState toPbjPlatformState(
            @NonNull com.hedera.hapi.platform.state.PlatformState previousState,
            @NonNull final PlatformStateValueAccumulator accumulator) {
        requireNonNull(accumulator);
        var builder = previousState.copyBuilder();

        if (accumulator.isCreationSoftwareVersionUpdated()) {
            builder.creationSoftwareVersion(
                    accumulator.getCreationSoftwareVersion().getPbjSemanticVersion());
        }

        if (accumulator.isRoundsNonAncientUpdated()) {
            builder.roundsNonAncient(accumulator.getRoundsNonAncient());
        }

        com.hedera.hapi.platform.state.ConsensusSnapshot.Builder consensusSnapshotBuilder;
        if (accumulator.isSnapshotUpdated()) {
            consensusSnapshotBuilder = accumulator.getSnapshot().copyBuilder();
        } else {
            consensusSnapshotBuilder = previousState
                    .consensusSnapshotOrElse(com.hedera.hapi.platform.state.ConsensusSnapshot.DEFAULT)
                    .copyBuilder();
        }

        if (accumulator.isRoundUpdated()) {
            consensusSnapshotBuilder.round(accumulator.getRound());
        }

        if (accumulator.isConsensusTimestampUpdated()) {
            consensusSnapshotBuilder.consensusTimestamp(toPbjTimestamp(accumulator.getConsensusTimestamp()));
        }

        builder.consensusSnapshot(consensusSnapshotBuilder);

        if (accumulator.isFreezeTimeUpdated()) {
            builder.freezeTime(toPbjTimestamp(accumulator.getFreezeTime()));
        }

        if (accumulator.isLastFrozenTimeUpdated()) {
            builder.lastFrozenTime(toPbjTimestamp(accumulator.getLastFrozenTime()));
        }

        if (accumulator.isLegacyRunningEventHashUpdated()) {
            if (accumulator.getLegacyRunningEventHash() == null) {
                builder.legacyRunningEventHash(Bytes.EMPTY);
            } else {
                builder.legacyRunningEventHash(
                        accumulator.getLegacyRunningEventHash().getBytes());
            }
        }

        if (accumulator.isLowestJudgeGenerationBeforeBirthRoundModeUpdated()) {
            builder.lowestJudgeGenerationBeforeBirthRoundMode(
                    accumulator.getLowestJudgeGenerationBeforeBirthRoundMode());
        }

        if (accumulator.isLastRoundBeforeBirthRoundModeUpdated()) {
            builder.lastRoundBeforeBirthRoundMode(accumulator.getLastRoundBeforeBirthRoundMode());
        }

        if (accumulator.isFirstVersionInBirthRoundModeUpdated()) {
            if (accumulator.getFirstVersionInBirthRoundMode() == null) {
                builder.firstVersionInBirthRoundMode((SemanticVersion) null);
            } else {
                builder.firstVersionInBirthRoundMode(
                        accumulator.getFirstVersionInBirthRoundMode().getPbjSemanticVersion());
            }
        }

        return builder.build();
    }

    /**
     * Converts an instance of {@link AddressBook} to the corresponding {@link com.hedera.hapi.platform.state.AddressBook}.
     * @param addressBook source of the data
     * @return the address book as PBJ object
     */
    @Nullable
    public static com.hedera.hapi.platform.state.AddressBook toPbjAddressBook(@Nullable final AddressBook addressBook) {
        if (addressBook == null) {
            return null;
        }
        Iterator<Address> addressIterator = addressBook.iterator();
        List<com.hedera.hapi.platform.state.Address> addresses = new ArrayList<>();

        while (addressIterator.hasNext()) {
            Address address = addressIterator.next();
            addresses.add(toPbjAddress(address));
        }

        return com.hedera.hapi.platform.state.AddressBook.newBuilder()
                .round(addressBook.getRound())
                .nextNodeId(com.hedera.hapi.platform.state.NodeId.newBuilder()
                        .id(addressBook.getNextNodeId().id()))
                .addresses(addresses)
                .build();
    }

    /**
     * Converts an instance of {@link com.hedera.hapi.platform.state.AddressBook addressBook)} to the corresponding {@link AddressBook}.
     * @param addressBook source of the data
     * @return the address book a domain object
     */
    public static @Nullable AddressBook fromPbjAddressBook(
            @Nullable final com.hedera.hapi.platform.state.AddressBook addressBook) {
        if (addressBook == null) {
            return null;
        }
        AddressBook result = new AddressBook(addressBook.addresses().stream()
                .map(PbjConverter::fromPbjAddress)
                .collect(toList()));
        result.setRound(addressBook.round());
        if (addressBook.nextNodeId() != null) {
            result.setNextNodeId(NodeId.of(addressBook.nextNodeId().id()));
        }
        return result;
    }

    @Nullable
    public static Timestamp toPbjTimestamp(@Nullable final Instant instant) {
        if (instant == null) {
            return null;
        }
        return new Timestamp(instant.getEpochSecond(), instant.getNano());
    }

    @Nullable
    public static Instant fromPbjTimestamp(@Nullable final Timestamp timestamp) {
        return timestamp == null ? null : Instant.ofEpochSecond(timestamp.seconds(), timestamp.nanos());
    }

    @NonNull
    private static com.hedera.hapi.platform.state.Address toPbjAddress(@NonNull final Address address) {
        final var builder = com.hedera.hapi.platform.state.Address.newBuilder()
                .id(com.hedera.hapi.platform.state.NodeId.newBuilder()
                        .id(address.getNodeId().id())
                        .build())
                .nickname(address.getNickname())
                .selfName(address.getSelfName())
                .weight(address.getWeight())
                .portInternal(address.getPortInternal())
                .portExternal(address.getPortExternal())
                .memo(address.getMemo());
        if (address.getHostnameInternal() != null) {
            builder.hostnameInternal(address.getHostnameInternal());
        }
        if (address.getHostnameExternal() != null) {
            builder.hostnameExternal(address.getHostnameExternal());
        }
        X509Certificate sigCert = address.getSigCert();
        if (sigCert != null) {
            try {
                builder.signingCertificate(Bytes.wrap(sigCert.getEncoded()));
            } catch (CertificateEncodingException e) {
                throw new UncheckedIOException("Not able to serialize signing x509 certificate", new IOException(e));
            }
        }
        X509Certificate agreeCert = address.getAgreeCert();
        if (agreeCert != null) {
            try {
                builder.agreementCertificate(Bytes.wrap(agreeCert.getEncoded()));
            } catch (CertificateEncodingException e) {
                throw new UncheckedIOException("Not able to serialize x509 certificate", new IOException(e));
            }
        }

        return builder.build();
    }

    @NonNull
    private static Address fromPbjAddress(@NonNull final com.hedera.hapi.platform.state.Address address) {
        requireNonNull(address.id());
        return new Address(
                NodeId.of(address.id().id()),
                address.nickname(),
                address.selfName(),
                address.weight(),
                address.hostnameInternal(),
                address.portInternal(),
                address.hostnameExternal(),
                address.portExternal(),
                fromPbjX509Certificate(address.signingCertificate()),
                fromPbjX509Certificate(address.agreementCertificate()),
                address.memo());
    }

    @Nullable
    private static SerializableX509Certificate fromPbjX509Certificate(@Nullable final Bytes bytes) {
        if (bytes == null || bytes.length() == 0) {
            // as of release 0.55.0, future address books in state will not have the agreement key serialized.
            return null;
        }
        final byte[] encoded = bytes.toByteArray();
        try {
            return new SerializableX509Certificate((X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded)));
        } catch (final CertificateException e) {
            throw new UncheckedIOException("Unable to deserialize x509 certificate", new IOException(e));
        }
    }

    private PbjConverter() {
        // empty
    }
}
