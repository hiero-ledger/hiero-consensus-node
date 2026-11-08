// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GenerateChannelIdentityCommandTest {

    @ParameterizedTest
    @ValueSource(strings = {"ED25519", "ECDSA_SECP256K1"})
    void bundleFileOmitsPrivateKey(final String scheme, @TempDir final Path dir) throws Exception {
        final var generator = newGenerator(scheme, dir.resolve("channel.json").toString());
        assertThat(generator.call()).isZero();
        final var fields = ClprArgs.readFlatJsonStringFields(dir.resolve("channel.json"));
        assertThat(fields).doesNotContainKey("privateKey");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ED25519", "ECDSA_SECP256K1"})
    void bundleFileHasOwnerOnlyPermissions(final String scheme, @TempDir final Path dir) throws Exception {
        final var generator = newGenerator(scheme, dir.resolve("channel.json").toString());
        assertThat(generator.call()).isZero();
        final var perms = Files.getPosixFilePermissions(dir.resolve("channel.json"));
        assertThat(perms)
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                .doesNotContain(
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_WRITE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_WRITE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ED25519", "ECDSA_SECP256K1"})
    void privateKeyFileIsWrittenAlongsideBundle(final String scheme, @TempDir final Path dir) throws Exception {
        final var bundlePath = dir.resolve("channel.json");
        final var generator = newGenerator(scheme, bundlePath.toString());
        assertThat(generator.call()).isZero();
        assertThat(dir.resolve("channel.json.key")).exists();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ED25519", "ECDSA_SECP256K1"})
    void privateKeyFileHasOwnerOnlyPermissions(final String scheme, @TempDir final Path dir) throws Exception {
        final var generator = newGenerator(scheme, dir.resolve("channel.json").toString());
        assertThat(generator.call()).isZero();
        final var perms = Files.getPosixFilePermissions(dir.resolve("channel.json.key"));
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
        final var target = dir.resolve("channel.json");
        Files.writeString(target, "existing");
        final var generator = newGenerator("ED25519", target.toString());
        assertThatThrownBy(generator::call).isInstanceOf(Exception.class);
    }

    @Test
    void bundleWriteFailsIfTargetIsSymlink(@TempDir final Path dir) throws Exception {
        final var real = dir.resolve("real.json");
        Files.writeString(real, "real");
        final var link = dir.resolve("channel.json");
        Files.createSymbolicLink(link, real);
        final var generator = newGenerator("ED25519", link.toString());
        assertThatThrownBy(generator::call).isInstanceOf(Exception.class);
    }

    @Test
    void failsWhenOutFileNotProvided() {
        final var generator = new GenerateChannelIdentityCommand();
        generator.signatureScheme = "ED25519";
        generator.outFile = null;
        assertThatThrownBy(generator::call).isInstanceOf(Exception.class);
    }

    // -------------------------------------------------------------------------

    private static GenerateChannelIdentityCommand newGenerator(final String scheme, final String outFile) {
        final var g = new GenerateChannelIdentityCommand();
        g.signatureScheme = scheme;
        g.outFile = outFile;
        return g;
    }
}
