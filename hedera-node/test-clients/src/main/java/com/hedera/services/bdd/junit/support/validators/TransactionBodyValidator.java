// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import static com.hedera.node.app.hapi.utils.CommonUtils.extractTransactionBody;
import static com.hedera.node.app.hapi.utils.CommonUtils.functionOf;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCreate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.services.bdd.junit.support.RecordStreamValidator;
import com.hedera.services.bdd.junit.support.RecordWithSidecars;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * This validator checks all the transactions submitted have {@link com.hederahashgraph.api.proto.java.TransactionBody}
 * set by trying to extract the {@link HederaFunctionality} of each transaction body.
 */
public class TransactionBodyValidator implements RecordStreamValidator {
    private static final Logger log = LogManager.getLogger(TransactionBodyValidator.class);

    @Override
    public void validateRecordsAndSidecars(@NonNull final List<RecordWithSidecars> recordsWithSidecars) {
        requireNonNull(recordsWithSidecars);
        for (final var recordWithSidecars : recordsWithSidecars) {
            final var items = recordWithSidecars.recordFile().getRecordStreamItemsList();
            for (final var item : items) {
                try {
                    final var txnBody = extractTransactionBody(item.getTransaction());
                    final var function = functionOf(txnBody);
                    final var receipt = item.getRecord().getReceipt();
                    if (function == ContractCreate && receipt.getStatus() == SUCCESS) {
                        final var createdId = receipt.getContractID();
                        // Assert the body had a self-managed key based on this created id
                        if (txnBody.getContractCreateInstance().getAdminKey().hasContractID()) {
                            Assertions.assertEquals(
                                    txnBody.getContractCreateInstance()
                                            .getAdminKey()
                                            .getContractID()
                                            .getContractNum(),
                                    createdId.getContractNum(),
                                    "Contract create transaction does not have admin key set to self manage");
                        }
                    }
                } catch (InvalidProtocolBufferException | UnknownHederaFunctionality e) {
                    log.error("Unable to parse and classify item {}", item, e);
                    throw new IllegalStateException(e);
                }
            }
        }
    }
}
