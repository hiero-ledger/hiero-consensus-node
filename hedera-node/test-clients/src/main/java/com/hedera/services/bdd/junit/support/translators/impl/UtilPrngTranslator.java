// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

public class UtilPrngTranslator implements BlockTransactionPartsTranslator {
    @Override
    public SingleTransactionRecord translate(
            @NonNull final BlockTransactionParts parts,
            @NonNull final BaseTranslator baseTranslator,
            @NonNull final List<StateChange> remainingStateChanges,
            @NonNull final List<TraceData> followingUnitTraces) {
        return baseTranslator.recordFrom(
                parts,
                (receiptBuilder, recordBuilder) -> {
                    if (parts.status() == SUCCESS) {
                        parts.outputIfPresent(TransactionOutput.TransactionOneOfType.UTIL_PRNG)
                                .map(TransactionOutput::utilPrngOrThrow)
                                .ifPresent(utilPrng -> {
                                    switch (utilPrng.entropy().kind()) {
                                        case UNSET ->
                                            throw new IllegalStateException(
                                                    "Successful UtilPrng output missing entropy");
                                        case PRNG_BYTES -> recordBuilder.prngBytes(utilPrng.prngBytesOrThrow());
                                        case PRNG_NUMBER -> recordBuilder.prngNumber(utilPrng.prngNumberOrThrow());
                                    }
                                });
                    }
                },
                remainingStateChanges,
                followingUnitTraces);
    }
}
