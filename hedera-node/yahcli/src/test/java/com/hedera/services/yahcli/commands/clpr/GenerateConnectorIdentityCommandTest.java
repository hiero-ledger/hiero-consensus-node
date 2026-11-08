// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.clpr.ClprSignatureScheme;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GenerateConnectorIdentityCommandTest {

    @ParameterizedTest
    @EnumSource(
            value = ClprSignatureScheme.class,
            names = {"ED25519", "ECDSA_SECP256K1"})
    void bundleFileHasOwnerOnlyPermissions(final ClprSignatureScheme scheme, @TempDir final Path dir) throws Exception {
        final var generator = newGenerator(
                scheme.name(), "42".repeat(32), dir.resolve("connector.json").toString());
        assertThat(generator.call()).isZero();
        final var perms = Files.getPosixFilePermissions(dir.resolve("connector.json"));
        assertThat(perms)
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                .doesNotContain(
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_WRITE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_WRITE);
    }

    @ParameterizedTest
    @EnumSource(
            value = ClprSignatureScheme.class,
            names = {"ED25519", "ECDSA_SECP256K1"})
    void privateKeyFileIsWrittenAlongsideBundle(final ClprSignatureScheme scheme, @TempDir final Path dir)
            throws Exception {
        final var generator = newGenerator(
                scheme.name(), "42".repeat(32), dir.resolve("connector.json").toString());
        assertThat(generator.call()).isZero();
        assertThat(dir.resolve("connector.json.key")).exists();
    }

    @ParameterizedTest
    @EnumSource(
            value = ClprSignatureScheme.class,
            names = {"ED25519", "ECDSA_SECP256K1"})
    void privateKeyFileHasOwnerOnlyPermissions(final ClprSignatureScheme scheme, @TempDir final Path dir)
            throws Exception {
        final var generator = newGenerator(
                scheme.name(), "42".repeat(32), dir.resolve("connector.json").toString());
        assertThat(generator.call()).isZero();
        final var perms = Files.getPosixFilePermissions(dir.resolve("connector.json.key"));
        assertThat(perms)
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                .doesNotContain(
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_WRITE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_WRITE);
    }

    @Test
    void bundleWriteFailsIfTargetAlreadyExists(@TempDir final Path dir) throws Exception {
        final var target = dir.resolve("connector.json");
        Files.writeString(target, "existing");
        final var generator = newGenerator("ED25519", "42".repeat(32), target.toString());
        assertThatThrownBy(generator::call).isInstanceOf(Exception.class);
    }

    @Test
    void bundleWriteFailsIfTargetIsSymlink(@TempDir final Path dir) throws Exception {
        final var real = dir.resolve("real.json");
        Files.writeString(real, "real");
        final var link = dir.resolve("connector.json");
        Files.createSymbolicLink(link, real);
        final var generator = newGenerator("ED25519", "42".repeat(32), link.toString());
        assertThatThrownBy(generator::call).isInstanceOf(Exception.class);
    }

    // -------------------------------------------------------------------------

    private static GenerateConnectorIdentityCommand newGenerator(
            final String scheme, final String channelIdHex, final String outFile) {
        final var g = new GenerateConnectorIdentityCommand();
        g.channelIdHex = channelIdHex;
        g.signatureScheme = scheme;
        g.outFile = outFile;
        return g;
    }
}
