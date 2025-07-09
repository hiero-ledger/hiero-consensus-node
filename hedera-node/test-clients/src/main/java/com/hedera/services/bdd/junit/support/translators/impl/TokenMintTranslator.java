// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.TokenType.NON_FUNGIBLE_UNIQUE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
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
                            final var mintedSerialNos = baseTranslator.nextNMints(tokenId, numMints);
                            receiptBuilder.serialNumbers(List.copyOf(mintedSerialNos));
                            final var iter = remainingStateChanges.listIterator();
                            while (iter.hasNext()) {
                                final var stateChange = iter.next();
                                if (stateChange.hasMapUpdate()
                                        && stateChange
                                                .mapUpdateOrThrow()
                                                .keyOrThrow()
                                                .hasNftIdKey()) {
                                    final var nftId = stateChange
                                            .mapUpdateOrThrow()
                                            .keyOrThrow()
                                            .nftIdKeyOrThrow();
                                    if (!nftId.tokenIdOrThrow().equals(tokenId)) {
                                        continue;
                                    }
                                    final var serialNo = nftId.serialNumber();
                                    if (mintedSerialNos.remove(serialNo)) {
                                        iter.remove();
                                        if (mintedSerialNos.isEmpty()) {
                                            final var newTotalSupply = baseTranslator.newTotalSupply(tokenId, numMints);
                                            receiptBuilder.newTotalSupply(newTotalSupply);
                                            return;
                                        }
                                    }
                                }
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
}
