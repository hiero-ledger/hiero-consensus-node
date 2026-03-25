// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.records.BlockRecordService;
import org.junit.jupiter.api.Test;

class ServiceScopeLookupTest {

    private final ServiceScopeLookup subject = new ServiceScopeLookup();

    @Test
    void mapsMigrationRootHashVoteToBlockRecordService() {
        final var txnBody = TransactionBody.newBuilder()
                .migrationRootHashVote(
                        com.hedera.hapi.services.auxiliary.blockrecords.MigrationRootHashVoteTransactionBody.DEFAULT)
                .build();

        assertEquals(BlockRecordService.NAME, subject.getServiceName(txnBody));
    }
}
