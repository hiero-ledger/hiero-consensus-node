// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import com.google.protobuf.util.JsonFormat;
import com.hederahashgraph.api.proto.java.ClprGetLedgerConfigurationResponse;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.Timestamp;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A ledger configuration file written by {@code get-ledger-configuration --out}: the {@code configuration} and, when
 * present, the {@code configurationStateProof}. A file holding only the configuration object is also accepted.
 *
 * @param configuration the ledger configuration
 * @param stateProof the configuration state proof, or {@code null} if absent
 */
record ClprLedgerConfigFile(
        ClprLedgerConfiguration configuration, @Nullable byte[] stateProof) {

    static ClprLedgerConfigFile read(final Path path) throws IOException {
        final var json = Files.readString(path);
        final var response = ClprGetLedgerConfigurationResponse.newBuilder();
        JsonFormat.parser().ignoringUnknownFields().merge(json, response);
        if (response.hasConfiguration()) {
            final var proof = response.getConfigurationStateProof();
            return new ClprLedgerConfigFile(response.getConfiguration(), proof.isEmpty() ? null : proof.toByteArray());
        }
        final var configuration = ClprLedgerConfiguration.newBuilder();
        JsonFormat.parser().ignoringUnknownFields().merge(json, configuration);
        return new ClprLedgerConfigFile(configuration.build(), null);
    }

    String chainId() {
        return configuration.getChainId();
    }

    byte[] serviceAddress() {
        return configuration.getServiceAddress().toByteArray();
    }

    /** The configuration timestamp, or {@code null} if absent. */
    @Nullable
    Timestamp timestamp() {
        return configuration.hasTimestamp() ? configuration.getTimestamp() : null;
    }

    byte[] initialTrustAnchor() {
        return configuration.getInitialTrustAnchor().toByteArray();
    }

    /**
     * Parses {@code <seconds>[.<fraction>]}, where the fraction is up to nine decimal digits of a second, as in
     * {@code 1700000000.000000123}.
     */
    static Timestamp parseTimestamp(final String text) {
        final var parts = text.trim().split("\\.", -1);
        if (parts.length > 2 || parts[0].isEmpty() || (parts.length == 2 && parts[1].length() > 9)) {
            throw new IllegalArgumentException("Expected <seconds>[.<up to 9 fraction digits>], got: " + text);
        }
        final int nanos = parts.length == 2 && !parts[1].isEmpty()
                ? Integer.parseInt((parts[1] + "00000000").substring(0, 9))
                : 0;
        return Timestamp.newBuilder()
                .setSeconds(Long.parseLong(parts[0]))
                .setNanos(nanos)
                .build();
    }
}
