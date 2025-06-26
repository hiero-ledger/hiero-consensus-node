// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Translates a file update transaction into a {@link SingleTransactionRecord}, updating
 * {@link BaseTranslator} context when a special file is changed.
 */
public class AtomicBatchTranslator implements BlockTransactionPartsTranslator {
    public static final long EXCHANGE_RATES_FILE_NUM = 112L;

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
                        for (final var stateChange : remainingStateChanges) {
                            if (stateChange.hasMapUpdate()
                                    && stateChange
                                            .mapUpdateOrThrow()
                                            .keyOrThrow()
                                            .hasFileIdKey()) {
                                final var fileId = stateChange
                                        .mapUpdateOrThrow()
                                        .keyOrThrow()
                                        .fileIdKeyOrThrow();
                                if (fileId.fileNum() == EXCHANGE_RATES_FILE_NUM) {
                                    final var contents = stateChange
                                            .mapUpdateOrThrow()
                                            .valueOrThrow()
                                            .fileValueOrThrow()
                                            .contents();
                                    try {
                                        // Set the active rates from state changes happened inside AtomicBatch
                                        final var activeRates = ExchangeRateSet.PROTOBUF.parse(contents);
                                        receiptBuilder.exchangeRate(activeRates);
                                    } catch (ParseException e) {
                                        throw new IllegalStateException(
                                                "Rates file updated with unparseable contents", e);
                                    }
                                }
                            }
                        }
                    }
                },
                remainingStateChanges,
                followingUnitTraces);
    }
}
