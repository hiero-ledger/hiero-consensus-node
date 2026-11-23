// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.clpr.ClprSignatureScheme;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.services.bdd.spec.transactions.clpr.HapiClprCreateConnector;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ClprConnectorIdentityTest {
    @ParameterizedTest
    @EnumSource(
            value = ClprSignatureScheme.class,
            names = {"ED25519", "ECDSA_SECP256K1"})
    void registrationKeyMatchesGeneratedIdentity(final ClprSignatureScheme scheme, @TempDir final Path dir)
            throws Exception {
        final var generator = new GenerateConnectorIdentityCommand();
        generator.signatureScheme = scheme.name();
        final var output = dir.resolve("connector.json");
        generator.outFile = output.toString();
        assertThat(generator.call()).isZero();
        final var identity = ClprArgs.readFlatJsonStringFields(output);
        final var key = CreateConnectorCommand.readIdentityKey(dir.resolve("connector.json.key"));
        final var op = new HapiClprCreateConnector();
        final var publicKey = scheme == ClprSignatureScheme.ED25519
                ? op.ed25519Identity(key).identityKey().getEd25519().toByteArray()
                : MiscCryptoUtils.decompressSecp256k1(op.secp256k1Identity(key)
                        .identityKey()
                        .getECDSASecp256K1()
                        .toByteArray());
        assertThat(publicKey).isEqualTo(ClprArgs.parseHex(identity.get("publicKey")));
    }
}
