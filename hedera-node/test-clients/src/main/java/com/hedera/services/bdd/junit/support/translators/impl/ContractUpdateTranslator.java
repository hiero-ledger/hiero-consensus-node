// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * A translator for contract update transactions.
 */
public class ContractUpdateTranslator implements BlockTransactionPartsTranslator {
    @Override
    public SingleTransactionRecord translate(
            @NonNull final BlockTransactionParts parts,
            @NonNull final BaseTranslator baseTranslator,
            @NonNull final List<StateChange> remainingStateChanges) {
        return baseTranslator.recordFrom(parts, (receiptBuilder, recordBuilder) -> {
            if (parts.status() == SUCCESS) {
                final var iter = remainingStateChanges.listIterator();
                while (iter.hasNext()) {
                    final var stateChange = iter.next();
                    if (stateChange.hasMapUpdate()
                            && stateChange.mapUpdateOrThrow().keyOrThrow().hasAccountIdKey()) {
                        final var account =
                                stateChange.mapUpdateOrThrow().valueOrThrow().accountValueOrThrow();
                        if (account.smartContract()) {
                            iter.remove();
                            final var contractId = ContractID.newBuilder()
                                    .shardNum(account.accountIdOrThrow().shardNum())
                                    .realmNum(account.accountIdOrThrow().realmNum())
                                    .contractNum(account.accountIdOrThrow().accountNumOrThrow())
                                    .build();
                            receiptBuilder.contractID(contractId);
                            return;
                        }
                    }
                }
            }
        });
    }
}
