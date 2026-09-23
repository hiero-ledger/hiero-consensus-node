// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared argument-parsing helpers for CLPR yahcli commands.
 *
 * <p>Covers the common flag idioms across the CLPR subcommand surface: required vs. optional
 * hex-encoded byte strings, {@code account-id} parsing ({@code shard.realm.num} or
 * {@code num}), {@code contract-id} parsing (same forms), and flat JSON-bundle field
 * extraction so {@code complete-*} commands can load identities from a generated
 * {@code .json} blob without manually wiring every flag.
 */
final class ClprArgs {
    private ClprArgs() {}

    static byte[] requiredBytes(final String flagName, @Nullable final String hex) {
        if (hex == null || hex.isBlank()) {
            throw new IllegalArgumentException("--" + flagName + " is required");
        }
        return parseHex(hex);
    }

    @Nullable
    static byte[] optionalBytes(@Nullable final String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        return parseHex(hex);
    }

    static byte[] readBytesFile(final Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    /**
     * Reads a flat JSON file (one level deep, only string-valued fields) and returns a
     * {@code Map<fieldName, stringValue>}. Intended for parsing the identity bundles
     * produced by {@code generate-channel-identity} / {@code generate-connector-identity},
     * which have a fixed shape — keeps yahcli dependency-light by avoiding a real JSON library.
     */
    static Map<String, String> readFlatJsonStringFields(final Path path) throws IOException {
        final var content = Files.readString(path);
        final var pattern = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"");
        final var matcher = pattern.matcher(content);
        final var result = new HashMap<String, String>();
        while (matcher.find()) {
            result.put(matcher.group(1), matcher.group(2));
        }
        return result;
    }

    static byte[] parseHex(final String raw) {
        var s = raw.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if ((s.length() & 1) == 1) {
            throw new IllegalArgumentException("Hex string has an odd number of characters: " + raw);
        }
        final var out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            final int hi = Character.digit(s.charAt(2 * i), 16);
            final int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex character in: " + raw);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    static AccountID parseAccountId(final String literal) {
        final long[] parts = parseDotTriple(literal);
        return AccountID.newBuilder()
                .setShardNum(parts[0])
                .setRealmNum(parts[1])
                .setAccountNum(parts[2])
                .build();
    }

    static ContractID parseContractId(final String literal) {
        final long[] parts = parseDotTriple(literal);
        return ContractID.newBuilder()
                .setShardNum(parts[0])
                .setRealmNum(parts[1])
                .setContractNum(parts[2])
                .build();
    }

    private static long[] parseDotTriple(final String literal) {
        final var parts = literal.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Expected entity id of form <shard>.<realm>.<num> but got: " + literal);
        }
        try {
            return new long[] {Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2])};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Entity id parts must be longs: " + literal, e);
        }
    }
}
