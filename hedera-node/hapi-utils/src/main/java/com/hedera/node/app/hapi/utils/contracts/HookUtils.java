// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.contracts;

import static com.hedera.node.app.hapi.utils.MiscCryptoUtils.keccak256DigestOf;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.hooks.LambdaMappingEntry;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.utility.ByteUtils;

public class HookUtils {
    /**
     * Returns a minimal representation of the given bytes, stripping leading zeros.
     * @param bytes the bytes to strip leading zeros from
     * @return the minimal representation of the bytes, or an empty bytes if all bytes were stripped
     */
    public static Bytes minimalRepresentationOf(@NonNull final com.hedera.pbj.runtime.io.buffer.Bytes bytes) {
        int i = 0;
        int n = (int) bytes.length();
        while (i < n && bytes.getByte(i) == 0) {
            i++;
        }
        if (i == n) {
            return com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY;
        } else if (i == 0) {
            return bytes;
        } else {
            return bytes.slice(i, n - i);
        }
    }

    /**
     * Returns the slot key for a mapping entry, given the left-padded mapping slot and the entry.
     * <p>
     * C.f. Solidity docs <a href="https://docs.soliditylang.org/en/latest/internals/layout_in_storage.html">here</a>.
     * @param leftPaddedMappingSlot the left-padded mapping slot
     * @param entry the mapping entry
     * @return the slot key for the mapping entry
     */
    public static Bytes slotKeyOfMappingEntry(
            @NonNull final com.hedera.pbj.runtime.io.buffer.Bytes leftPaddedMappingSlot,
            @NonNull final LambdaMappingEntry entry) {
        final com.hedera.pbj.runtime.io.buffer.Bytes hK;
        if (entry.hasKey()) {
            hK = ByteUtils.leftPad32(entry.keyOrThrow());
        } else {
            hK = keccak256DigestOf(entry.preimageOrThrow());
        }
        return keccak256DigestOf(hK.append(leftPaddedMappingSlot));
    }

    /**
     * Checks if the crypto transfer operation has any hooks set in any of the account amounts or nft transfers.
     *
     * @param op the crypto transfer operation
     * @return true if the crypto transfer operation has any hooks set in any of the account amounts or nft transfers
     */
    public static boolean hasHookExecutions(final @NonNull CryptoTransferTransactionBody op) {
        for (final AccountAmount aa : op.transfersOrElse(TransferList.DEFAULT).accountAmounts()) {
            if (aa.hasPreTxAllowanceHook() || aa.hasPrePostTxAllowanceHook()) {
                return true;
            }
        }
        for (final TokenTransferList ttl : op.tokenTransfers()) {
            for (final AccountAmount aa : ttl.transfers()) {
                if (aa.hasPreTxAllowanceHook() || aa.hasPrePostTxAllowanceHook()) {
                    return true;
                }
            }
            for (final NftTransfer nft : ttl.nftTransfers()) {
                if (nft.hasPreTxSenderAllowanceHook()
                        || nft.hasPrePostTxSenderAllowanceHook()
                        || nft.hasPreTxReceiverAllowanceHook()
                        || nft.hasPrePostTxReceiverAllowanceHook()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Converts a ContractID to an AccountID.
     *
     * @param contractID the ContractID to convert
     * @return the corresponding AccountID
     */
    public static AccountID asAccountId(@NonNull final ContractID contractID) {
        requireNonNull(contractID);
        return AccountID.newBuilder()
                .shardNum(contractID.shardNum())
                .realmNum(contractID.realmNum())
                .accountNum(contractID.contractNumOrThrow())
                .build();
    }
}
