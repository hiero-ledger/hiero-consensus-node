// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import com.hedera.node.app.hapi.utils.keys.Secp256k1Utils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.SignatureType;

/** Shared helpers for the generate-*-identity command tests. */
final class IdentityTestSupport {
    private static final Set<PosixFilePermission> OWNER_RW =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private IdentityTestSupport() {}

    /** Reads a {@code .key} file, asserting it has the exact documented format: {@code 0x} + 64 hex + newline. */
    static byte[] readKeyFile(final Path keyFile) throws IOException {
        final var text = Files.readString(keyFile);
        assertThat(text).matches("0x[0-9a-f]{64}\n");
        return HexFormat.of().parseHex(text.substring(2, 66));
    }

    /** Independently re-derives the on-wire public key (ED25519: 32 bytes; ECDSA: 64-byte X||Y) from a private key. */
    static byte[] derivePublicKey(final String scheme, final byte[] privateKeyBytes) {
        return switch (scheme) {
            case "ED25519" -> Ed25519Utils.extractEd25519PublicKey(Ed25519Utils.keyFrom(privateKeyBytes));
            case "ECDSA_SECP256K1" -> {
                final var priv = Secp256k1Utils.readECKeyFrom(privateKeyBytes);
                yield MiscCryptoUtils.decompressSecp256k1(Secp256k1Utils.extractEcdsaPublicKey(priv));
            }
            default -> throw new IllegalArgumentException("Unsupported scheme " + scheme);
        };
    }

    static boolean verifies(final String scheme, final byte[] digest, final byte[] signature, final byte[] publicKey) {
        final var type = "ED25519".equals(scheme) ? SignatureType.ED25519 : SignatureType.ECDSA_SECP256K1;
        return CryptographyProvider.getInstance().verifySync(digest, signature, publicKey, type);
    }

    static byte[] concat(final byte[]... parts) {
        int len = 0;
        for (final var p : parts) {
            len += p.length;
        }
        final var out = new byte[len];
        int off = 0;
        for (final var p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    /** Lists backups of {@code fileName} in {@code dir}, i.e. files named {@code <fileName>.<epoch>.bak}. */
    static List<Path> backupsOf(final Path dir, final String fileName) throws IOException {
        final var pattern = Pattern.compile(Pattern.quote(fileName) + "\\.\\d+\\.bak");
        try (final Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> pattern.matcher(p.getFileName().toString()).matches())
                    .toList();
        }
    }

    static void assertOwnerOnly(final Path path) throws IOException {
        assertThat(Files.getPosixFilePermissions(path)).isEqualTo(OWNER_RW);
    }
}
