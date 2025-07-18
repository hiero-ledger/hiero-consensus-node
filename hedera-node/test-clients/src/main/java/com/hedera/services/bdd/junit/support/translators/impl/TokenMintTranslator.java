// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.TokenType.NON_FUNGIBLE_UNIQUE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Translates a token mint transaction into a {@link SingleTransactionRecord}.
 */
public class TokenMintTranslator implements BlockTransactionPartsTranslator {
    private static final Logger log = LogManager.getLogger(TokenMintTranslator.class);

    @Override
    public SingleTransactionRecord translate(
            @NonNull final BlockTransactionParts parts,
            @NonNull final BaseTranslator baseTranslator,
            @NonNull final List<StateChange> remainingStateChanges,
            @Nullable final List<TraceData> tracesSoFar,
            @NonNull final List<TraceData> followingUnitTraces) {
        requireNonNull(parts);
        requireNonNull(baseTranslator);
        requireNonNull(remainingStateChanges);
        return baseTranslator.recordFrom(
                parts,
                (receiptBuilder, recordBuilder) -> {
                    if (parts.status() == SUCCESS) {
                        final var op = parts.body().tokenMintOrThrow();
                        final var tokenId = op.tokenOrThrow();
                        final var numMints = op.metadata().size();
                        if (numMints > 0 && baseTranslator.tokenTypeOrThrow(tokenId) == NON_FUNGIBLE_UNIQUE) {

                            // get the next N serials that were minted in this unit from highestPutSerialNos.
                            final var mintedSerialNos = baseTranslator.nextNMints(tokenId, numMints);
                            receiptBuilder.serialNumbers(List.copyOf(mintedSerialNos));

                            // validate all mintedSerialNos are in the state changes
                            for (StateChange stateChange : remainingStateChanges) {
                                final var nftId = findNftUpdate(stateChange, tokenId);
                                if (nftId != null) {
                                    final var serialNo = nftId.serialNumber();
                                    mintedSerialNos.remove(serialNo);
                                }
                            }

                            // if there are some missing serials, try finding them in mapDelete state changes
                            if (!mintedSerialNos.isEmpty() && parts.isBatchScoped()) {
                                for (StateChange stateChange : remainingStateChanges) {
                                    final var nftId = findNftDelete(stateChange, tokenId);
                                    if (nftId != null) {
                                        final var serialNo = nftId.serialNumber();
                                        mintedSerialNos.remove(serialNo);
                                    }
                                }
                            }

                            if (mintedSerialNos.isEmpty()) {
                                final var newTotalSupply = baseTranslator.newTotalSupply(tokenId, numMints);
                                receiptBuilder.newTotalSupply(newTotalSupply);
                                return;
                            }
                            log.error(
                                    "Not all mints had matching state changes found for successful mint with id {}",
                                    parts.transactionIdOrThrow());
                        } else {
                            final var amountMinted = op.amount();
                            final var newTotalSupply = baseTranslator.newTotalSupply(tokenId, amountMinted);
                            receiptBuilder.newTotalSupply(newTotalSupply);
                        }
                    }
                },
                remainingStateChanges,
                followingUnitTraces);
    }

    // check if given state change is mapUpdate nft id of a target token.
    private NftID findNftUpdate(StateChange stateChange, TokenID targetTokenId) {
        final var isUpdate = stateChange.hasMapUpdate()
                && stateChange.mapUpdateOrThrow().keyOrThrow().hasNftIdKey();
        final var nftId = isUpdate ? stateChange.mapUpdateOrThrow().keyOrThrow().nftIdKeyOrThrow() : null;
        return isUpdate && nftId.tokenIdOrThrow().equals(targetTokenId) ? nftId : null;
    }

    // check if given state change is mapDelete nft id of a target token.
    private NftID findNftDelete(StateChange stateChange, TokenID targetTokenId) {
        final var isDelete = stateChange.hasMapDelete()
                && stateChange.mapDeleteOrThrow().keyOrThrow().hasNftIdKey();
        final var nftId = isDelete ? stateChange.mapDeleteOrThrow().keyOrThrow().nftIdKeyOrThrow() : null;
        return isDelete && nftId.tokenIdOrThrow().equals(targetTokenId) ? nftId : null;
    }
}
