// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import com.hedera.services.bdd.junit.support.RecordStreamValidator;
import com.hedera.services.bdd.junit.support.RecordWithSidecars;
import com.hedera.services.stream.proto.RecordStreamItem;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assertions;

/** A simple validator that asserts a contract that was created appears in the record stream */
public class ContractExistenceValidator implements RecordStreamValidator {
    private final String name;
    private final Instant consensusTimestamp;

    public ContractExistenceValidator(final String name, final Instant consensusTimestamp) {
        this.name = name;
        this.consensusTimestamp = consensusTimestamp;
    }

    @Override
    public void validateRecordsAndSidecars(final List<RecordWithSidecars> recordFiles) {
        final var contractExists = new AtomicBoolean();

        BalanceReconciliationValidator.streamOfItemsFrom(recordFiles)
                .filter(this::isAtConsensusTime)
                .forEach(item -> {
                    final var receipt = item.getRecord().getReceipt();
                    if (receipt.hasContractID()) {
                        contractExists.set(true);
                    }
                });
        if (!contractExists.get()) {
            Assertions.fail("Expected '" + name + "' to be created at " + consensusTimestamp + ", but it was not");
        }
    }

    private boolean isAtConsensusTime(final RecordStreamItem item) {
        final var timestamp = item.getRecord().getConsensusTimestamp();
        final var timeHere = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
        return timeHere.equals(consensusTimestamp);
    }
}
