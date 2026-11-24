// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import com.hedera.cryptography.tss.TSS;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Real {@link TssVerifier} implementation that delegates to the native
 * {@link com.hedera.cryptography.tss.TSS#verifyTSS(byte[], byte[], byte[])} convenience API.
 *
 * <p>The composite {@code signature} bytes carry the peer ledger's hinTS verification key, the
 * hinTS aggregate signature, and the WRAPS recursive proof that binds that verification key to
 * the supplied {@code ledgerId}. The call therefore needs no separate per-ledger key registry —
 * the proof inside the signature is self-authenticating against {@code ledgerId}.
 */
public class NativeTssVerifier implements TssVerifier {
    private static final Logger log = LogManager.getLogger(NativeTssVerifier.class);

    @Override
    public boolean verifyTss(
            @NonNull final Bytes ledgerId, @NonNull final Bytes signature, @NonNull final Bytes blockRootHash) {
        try {
            final boolean ok =
                    TSS.verifyTSS(ledgerId.toByteArray(), signature.toByteArray(), blockRootHash.toByteArray());
            log.info(
                    "NativeTssVerifier.verifyTss RESULT ledgerId={} blockRootHash={} signatureBytes={} "
                            + "signatureHash={} ok={}",
                    shortHex(ledgerId),
                    shortHex(blockRootHash),
                    signature.length(),
                    shortHex(MiscCryptoUtils.keccak256DigestOf(signature)),
                    ok);
            log.debug(
                    "NativeTssVerifier.verifyTss RESULT ledgerId={} blockRootHash={} signatureBytes={} "
                            + "signatureHash={} ok={}",
                    shortHex(ledgerId),
                    shortHex(blockRootHash),
                    signature.length(),
                    shortHex(MiscCryptoUtils.keccak256DigestOf(signature)),
                    ok);
            log.debug(
                    "NativeTssVerifier.verifyTss(ledgerId={}, msgLen={}, sigLen={}, ok={})",
                    ledgerId,
                    blockRootHash.length(),
                    signature.length(),
                    ok);
            return ok;
        } catch (final IllegalArgumentException | IllegalStateException e) {
            log.warn("NativeTssVerifier.verifyTss rejected proof for ledgerId {}: {}", ledgerId, e.getMessage());
            return false;
        }
    }

    private static String shortHex(@NonNull final Bytes bytes) {
        final var hex = bytes.toHex();
        if (hex.length() <= 64) {
            return hex;
        }
        return hex.substring(0, 64) + "...";
    }
}
