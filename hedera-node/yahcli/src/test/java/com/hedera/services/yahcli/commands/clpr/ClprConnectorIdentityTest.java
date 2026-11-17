// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.clpr.ClprSignatureScheme;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import java.nio.file.Path;
import java.util.HexFormat;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.SignatureType;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ClprConnectorIdentityTest {
    @ParameterizedTest
    @EnumSource(
            value = ClprSignatureScheme.class,
            names = {"ED25519", "ECDSA_SECP256K1"})
    void generatedIdentitySignsConnectorIdAndServiceAddress(final ClprSignatureScheme scheme, @TempDir final Path dir)
            throws Exception {
        final var generator = new GenerateConnectorIdentityCommand();
        generator.channelIdHex = "42".repeat(32);
        generator.signatureScheme = scheme.name();
        final var output = dir.resolve("connector.json");
        generator.outFile = output.toString();
        assertThat(generator.call()).isZero();
        final var identity = ClprArgs.readFlatJsonStringFields(output);
        final var publicKey = ClprArgs.parseHex(identity.get("publicKey"));
        final var signature = ClprArgs.parseHex(identity.get("signature"));
        assertThat(signature).hasSize(64);
        assertThat(identity.get("serviceAddress")).isEqualTo("0x000000000000000000000000000000000000016e");
        final var preimage = HexFormat.of()
                .parseHex(identity.get("connectorId").substring(2) + "000000000000000000000000000000000000016e");
        final var digest = MiscCryptoUtils.keccak256DigestOf(preimage);
        final var type = scheme == ClprSignatureScheme.ED25519 ? SignatureType.ED25519 : SignatureType.ECDSA_SECP256K1;
        assertThat(CryptographyProvider.getInstance().verifySync(digest, signature, publicKey, type))
                .isTrue();
        preimage[0] ^= 1;
        assertThat(CryptographyProvider.getInstance()
                        .verifySync(MiscCryptoUtils.keccak256DigestOf(preimage), signature, publicKey, type))
                .isFalse();
    }
}
