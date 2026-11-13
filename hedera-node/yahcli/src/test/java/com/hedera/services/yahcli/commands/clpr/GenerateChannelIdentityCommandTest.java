// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.yahcli.commands.clpr.IdentityTestSupport.assertOwnerOnly;
import static com.hedera.services.yahcli.commands.clpr.IdentityTestSupport.backupsOf;
import static com.hedera.services.yahcli.commands.clpr.IdentityTestSupport.concat;
import static com.hedera.services.yahcli.commands.clpr.IdentityTestSupport.derivePublicKey;
import static com.hedera.services.yahcli.commands.clpr.IdentityTestSupport.readKeyFile;
import static com.hedera.services.yahcli.commands.clpr.IdentityTestSupport.verifies;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GenerateChannelIdentityCommandTest {

    @ParameterizedTest
    @ValueSource(strings = {"ED25519", "ECDSA_SECP256K1"})
    void bundleFileOmitsPrivateKey(final String scheme, @TempDir final Path dir) throws Exception {
        final var bundlePath = dir.resolve("channel.json");
        assertThat(newGenerator(scheme, bundlePath.toString()).call()).isZero();
        final var fields = ClprArgs.readFlatJsonStringFields(bundlePath);
        assertThat(fields).doesNotContainKey("privateKey");
        // Nor may the key material appear anywhere in the bundle under another name.
        final var keyHex = HexFormat.of().formatHex(readKeyFile(dir.resolve("channel.json.key")));
        assertThat(Files.readString(bundlePath)).doesNotContain(keyHex);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ED25519", "ECDSA_SECP256K1"})
    void bundleAndKeyFilesHaveOwnerOnlyPermissions(final String scheme, @TempDir final Path dir) throws Exception {
        assertThat(newGenerator(scheme, dir.resolve("channel.json").toString()).call())
                .isZero();
        assertOwnerOnly(dir.resolve("channel.json"));
        assertOwnerOnly(dir.resolve("channel.json.key"));
    }

    /**
     * Round trip: the {@code .key} file alone must be enough to re-derive every public field in the bundle,
     * and the bundle's reveal signature must verify under that derived key.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ED25519", "ECDSA_SECP256K1"})
    void keyFileRoundTripsToBundle(final String scheme, @TempDir final Path dir) throws Exception {
        final var bundlePath = dir.resolve("channel.json");
        assertThat(newGenerator(scheme, bundlePath.toString()).call()).isZero();

        final var bundle = ClprArgs.readFlatJsonStringFields(bundlePath);
        final var derivedPublicKey = derivePublicKey(scheme, readKeyFile(dir.resolve("channel.json.key")));
        final var channelId = ClprArgs.parseHex(bundle.get("channelId"));

        assertThat(bundle.get("signatureScheme")).isEqualTo(scheme);
        assertThat(ClprArgs.parseHex(bundle.get("publicKey"))).isEqualTo(derivedPublicKey);
        assertThat(ClprArgs.parseHex(bundle.get("ownershipCommitment")))
                .isEqualTo(MiscCryptoUtils.keccak256DigestOf(concat(channelId, derivedPublicKey)));
        final var digest = MiscCryptoUtils.keccak256DigestOf(channelId);
        final var signature = ClprArgs.parseHex(bundle.get("signature"));
        assertThat(verifies(scheme, digest, signature, derivedPublicKey)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ED25519", "ECDSA_SECP256K1"})
    void existingBundleIsBackedUpNotOverwritten(final String scheme, @TempDir final Path dir) throws Exception {
        final var target = dir.resolve("channel.json");
        Files.writeString(target, "old-bundle");

        assertThat(newGenerator(scheme, target.toString()).call()).isZero();

        final var backups = backupsOf(dir, "channel.json");
        assertThat(backups).hasSize(1);
        assertThat(Files.readString(backups.getFirst())).isEqualTo("old-bundle");
        assertOwnerOnly(backups.getFirst());
        assertThat(ClprArgs.readFlatJsonStringFields(target)).containsKey("channelId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ED25519", "ECDSA_SECP256K1"})
    void existingKeyFileIsBackedUpNotDestroyed(final String scheme, @TempDir final Path dir) throws Exception {
        final var keyPath = dir.resolve("channel.json.key");
        Files.writeString(keyPath, "old-key-material");

        assertThat(newGenerator(scheme, dir.resolve("channel.json").toString()).call())
                .isZero();

        final var backups = backupsOf(dir, "channel.json.key");
        assertThat(backups).hasSize(1);
        assertThat(Files.readString(backups.getFirst())).isEqualTo("old-key-material");
        assertOwnerOnly(backups.getFirst());
        // The live key file is the new one and round-trips (format asserted inside readKeyFile).
        assertThat(readKeyFile(keyPath)).hasSize(32);
    }

    @Test
    void repeatedRunsKeepEveryPriorKey(@TempDir final Path dir) throws Exception {
        final var target = dir.resolve("channel.json");
        for (int i = 0; i < 3; i++) {
            assertThat(newGenerator("ED25519", target.toString()).call()).isZero();
        }
        assertThat(backupsOf(dir, "channel.json")).hasSize(2);
        assertThat(backupsOf(dir, "channel.json.key")).hasSize(2);
    }

    @Test
    void symlinkedBundleIsRefusedAndLeavesNothingBehind(@TempDir final Path dir) throws Exception {
        final var real = dir.resolve("real.json");
        Files.writeString(real, "real");
        final var link = dir.resolve("channel.json");
        Files.createSymbolicLink(link, real);

        assertThatThrownBy(newGenerator("ED25519", link.toString())::call).isInstanceOf(IOException.class);

        assertThat(Files.readString(real)).isEqualTo("real");
        assertThat(dir.resolve("channel.json.key")).doesNotExist();
        assertThat(backupsOf(dir, "channel.json")).isEmpty();
    }

    @Test
    void symlinkedKeyFileIsRefusedAndLeavesNothingBehind(@TempDir final Path dir) throws Exception {
        final var real = dir.resolve("real.key");
        Files.writeString(real, "real-key");
        Files.createSymbolicLink(dir.resolve("channel.json.key"), real);

        assertThatThrownBy(newGenerator("ED25519", dir.resolve("channel.json").toString())::call)
                .isInstanceOf(IOException.class);

        assertThat(Files.readString(real)).isEqualTo("real-key");
        assertThat(dir.resolve("channel.json")).doesNotExist();
    }

    @Test
    void failedBundleWriteDoesNotOrphanNewKey(@TempDir final Path dir) throws Exception {
        // A directory at the bundle path passes the symlink check but cannot be replaced.
        Files.createDirectory(dir.resolve("channel.json"));

        assertThatThrownBy(newGenerator("ED25519", dir.resolve("channel.json").toString())::call)
                .isInstanceOf(IOException.class);

        assertThat(dir.resolve("channel.json.key")).doesNotExist();
    }

    @Test
    void failsWhenOutFileNotProvided() {
        final var generator = new GenerateChannelIdentityCommand();
        generator.signatureScheme = "ED25519";
        generator.outFile = null;
        assertThatThrownBy(generator::call).isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------

    private static GenerateChannelIdentityCommand newGenerator(final String scheme, final String outFile) {
        final var g = new GenerateChannelIdentityCommand();
        g.signatureScheme = scheme;
        g.outFile = outFile;
        return g;
    }
}
