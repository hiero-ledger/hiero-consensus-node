// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hapi.utils.EthSigsUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;

/**
 * Encapsulates the logic for reading blocked accounts from file.
 */
public class BlocklistParser {
    private static final Logger log = LogManager.getLogger(BlocklistParser.class);

    /**
     * Makes sure that all blocked accounts contained in the blocklist resource are present in state,
     * and creates their definitions (if necessary).
     *
     * <p><b>Note: this method assumes that blocklists are enabled</b> – it does not check that config property
     * @param blocklistResourceName the blocklist resource
     * @return a list of blocked account info records
     */
    public List<BlockedInfo> parse(@NonNull final String blocklistResourceName) {
        final List<String> fileLines = readFileLines(blocklistResourceName);
        if (fileLines.isEmpty()) return Collections.emptyList();

        return parseBlockList(fileLines);
    }

    private List<String> readFileLines(@NonNull final String blocklistResourceName) {
        try {
            return readPrivateKeyBlocklist(blocklistResourceName);
        } catch (Exception e) {
            log.error("Failed to read blocklist resource {}", blocklistResourceName, e);
            return Collections.emptyList();
        }
    }

    private static List<BlockedInfo> parseBlockList(final List<String> fileLines) {
        final List<BlockedInfo> blocklist;
        try {
            final var columnHeaderLine = fileLines.get(0); // Assume that the first line is the header
            final var blocklistLines = fileLines.subList(1, fileLines.size());
            final var columnCount = columnHeaderLine.split(",").length;
            blocklist = blocklistLines.stream()
                    .map(line -> parseCSVLine(line, columnCount))
                    .toList();
        } catch (IllegalArgumentException iae) {
            log.error("Failed to parse blocklist", iae);
            return Collections.emptyList();
        }
        return blocklist;
    }

    @NonNull
    private static List<String> readPrivateKeyBlocklist(@NonNull final String fileName) {
        try (final var inputStream = BlocklistParser.class.getClassLoader().getResourceAsStream(fileName);
                final var reader = new BufferedReader(new InputStreamReader(requireNonNull(inputStream)))) {
            return reader.lines().toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load blocklist", e);
        }
    }

    /**
     * Parses a line from the blocklist resource and returns blocked account info record.
     *
     * The line should have the following format:
     * &lt;private key&gt;,&lt;memo&gt;
     *     where &lt;private key&gt; is a hex-encoded private key
     *     and &lt;memo&gt; is a memo for the blocked account
     *     and both values are comma-separated.
     *
     * The resulting blocked account info record contains the EVM address derived from the private key, and the memo.
     *
     * @param line line from the blocklist resource
     * @param columnCount number of comma-separated values in a line
     * @return blocked account info record
     */
    @NonNull
    private static BlockedInfo parseCSVLine(final @NonNull String line, final int columnCount) {
        final var parts = line.split(",", -1);
        if (parts.length != columnCount) {
            throw new IllegalArgumentException("Invalid line in blocklist resource: " + line);
        }

        final byte[] privateKeyBytes;
        try {
            privateKeyBytes = HexFormat.of().parseHex(parts[0]);
        } catch (IllegalArgumentException iae) {
            throw new IllegalArgumentException("Failed to decode line " + line, iae);
        }

        final var publicKeyBytes = ecdsaPrivateToPublicKey(privateKeyBytes);
        final var evmAddressBytes = EthSigsUtils.recoverAddressFromPubKey(publicKeyBytes);
        return new BlockedInfo(Bytes.wrap(evmAddressBytes), parts[1]);
    }

    /**
     * Derives the ECDSA public key bytes from the given ECDSA private key bytes.
     *
     * @param privateKeyBytes ECDSA private key bytes
     * @return ECDSA public key bytes
     */
    private static byte[] ecdsaPrivateToPublicKey(byte[] privateKeyBytes) {
        final var ecdsaSecp256K1Curve = SECNamedCurves.getByName("secp256k1");
        final var ecdsaSecp256K1Domain = new ECDomainParameters(
                ecdsaSecp256K1Curve.getCurve(),
                ecdsaSecp256K1Curve.getG(),
                ecdsaSecp256K1Curve.getN(),
                ecdsaSecp256K1Curve.getH());
        final var privateKeyData = new BigInteger(1, privateKeyBytes);
        var q = ecdsaSecp256K1Domain.getG().multiply(privateKeyData);
        var publicParams = new ECPublicKeyParameters(q, ecdsaSecp256K1Domain);
        return publicParams.getQ().getEncoded(true);
    }

    /**
     * Encapsulates the information about a blocked account.
     * @param evmAddress the EVM address of the blocked account
     * @param memo      the memo of the blocked account
     */
    public record BlockedInfo(@NonNull Bytes evmAddress, @NonNull String memo) {}
}
