// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.populateEthTxData;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.file.ReadableFileStore;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Computes the decoded size of an {@code EthereumTransaction}'s callData when it is delivered by
 * reference to an HFS file (empty inline callData plus a {@code FileID}) rather than inline.
 *
 * <p>The jumbo bytes throttle and the per-byte node fee are otherwise computed from the on-wire
 * {@code SignedTransaction} size, which for a file-referenced payload does not include the callData.
 * Callers add the value returned here to the on-wire size so that file-referenced and inline delivery
 * are accounted for consistently by size.
 *
 * <p>This helper never throws: any condition under which
 * {@link com.hedera.node.app.service.contract.impl.infra.EthereumCallDataHydration hydration} would not
 * occur, or would fail, yields {@code 0} extra bytes and the caller uses the on-wire size alone.
 * Validation of a malformed or oversize file is handled by the pre-handle checks, not here.
 */
public final class HydratedCallDataSize {
    private static final byte[] HEX_0X_PREFIX = {(byte) '0', (byte) 'x'};

    private HydratedCallDataSize() {}

    /**
     * Returns the number of decoded callData bytes that would be hydrated from the file referenced by the
     * given transaction, or {@code 0} if it is not a file-referenced {@code EthereumTransaction} (or the
     * referenced file is a system file, missing, deleted, or empty). Mirrors the size that
     * {@code EthereumCallDataHydration} would decode: hex characters (after an optional {@code 0x}) / 2.
     *
     * @param body the transaction body
     * @param fileStore a readable file store built from the same state the caller is metering against
     * @param firstUserEntityNum the first user-file entity number (system files are not hydrated)
     * @return decoded callData bytes to add to the on-wire size, or 0 when not applicable
     */
    public static long fileReferencedCallDataBytes(
            @NonNull final TransactionBody body,
            @NonNull final ReadableFileStore fileStore,
            final long firstUserEntityNum) {
        try {
            if (!body.hasEthereumTransaction()) {
                return 0;
            }
            final var ethBody = body.ethereumTransactionOrThrow();
            // A file reference exists only when the body carries a callData FileID.
            if (!ethBody.hasCallData()) {
                return 0;
            }
            // Hydration happens only when the inline data itself lacks callData (mirrors
            // EthereumCallDataHydration.requiresHydration). If inline callData is present it is already
            // counted in the on-wire size, so add nothing.
            final var ethTxData = populateEthTxData(ethBody.ethereumData().toByteArray());
            if (ethTxData == null || ethTxData.hasCallData()) {
                return 0;
            }
            final var fileId = ethBody.callDataOrThrow();
            if (fileId.fileNum() < firstUserEntityNum) {
                return 0;
            }
            final var file = fileStore.getFileLeaf(fileId);
            if (file == null || file.deleted()) {
                return 0;
            }
            final long hexLength = file.contents().length();
            if (hexLength == 0) {
                return 0;
            }
            // Decoded byte count = hex characters / 2, after stripping an optional leading "0x"
            // (matches ConversionUtils.removeIfAnyLeading0x + HexFormat.parseHex).
            final long strippedHexLength =
                    file.contents().matchesPrefix(HEX_0X_PREFIX) ? hexLength - HEX_0X_PREFIX.length : hexLength;
            return strippedHexLength / 2;
        } catch (final RuntimeException ignore) {
            // Never let a metering computation throw on the throttle/fee hot path; fall back to wire size.
            return 0;
        }
    }
}
