// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.token.AliasUtils.extractEvmAddress;
import static com.hedera.node.app.service.token.AliasUtils.isOfEvmAddressSize;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Translates a crypto create transaction into a {@link SingleTransactionRecord}.
 */
public class CryptoCreateTranslator implements BlockTransactionPartsTranslator {
    private static final Logger log = LogManager.getLogger(CryptoCreateTranslator.class);

    @Override
    public SingleTransactionRecord translate(
            @NonNull final BlockTransactionParts parts,
            @NonNull final BaseTranslator baseTranslator,
            @NonNull final List<StateChange> remainingStateChanges,
            @NonNull final List<TraceData> followingUnitTraces) {
        requireNonNull(parts);
        requireNonNull(baseTranslator);
        requireNonNull(remainingStateChanges);
        return baseTranslator.recordFrom(
                parts,
                (receiptBuilder, recordBuilder) -> {
                    if (parts.status() == SUCCESS && parts.outputs() != null) {
                        parts.outputs()
                                .forEach(transactionOutput -> receiptBuilder.accountID(
                                        transactionOutput.accountCreateOrThrow().createdAccountId()));

                        final var accountAlias = ((CryptoCreateTransactionBody)
                                        parts.transactionParts().body().data().value())
                                .alias();
                        if (!isOfEvmAddressSize(accountAlias)) {
                            final var maybeEvmAddress = extractEvmAddress(accountAlias);
                            if (maybeEvmAddress != null) {
                                recordBuilder.evmAddress(maybeEvmAddress);
                            }
                        }
                    }
                },
                remainingStateChanges,
                followingUnitTraces);
    }
}
