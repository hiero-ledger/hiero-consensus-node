// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.fee;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import org.junit.jupiter.api.Test;

class SmartContractFeeBuilderTest {
    private final TransactionBody.Builder transactionBodyBuilder =
            TransactionBody.newBuilder().memo("memo tx");
    private final SigValueObj signValueObj = new SigValueObj(2, 2, 2);
    private final SmartContractFeeBuilder smartContractFeeBuilder = new SmartContractFeeBuilder();

    @Test
    void assertGetFileContentQueryFeeMatrices() {
        var transactionBody = transactionBodyBuilder
                .ethereumTransaction(EthereumTransactionBody.newBuilder())
                .build();
        var result = smartContractFeeBuilder.getEthereumTransactionFeeMatrices(transactionBody, signValueObj);
        assertEquals(1, result.nodedata().constant());
        assertEquals(229, result.nodedata().bpt());
        assertEquals(2, result.nodedata().vpt());
        assertEquals(4, result.nodedata().bpr());
        assertEquals(0, result.nodedata().sbpr());

        assertEquals(1, result.networkdata().constant());
        assertEquals(229, result.networkdata().bpt());
        assertEquals(3, result.networkdata().vpt());
        assertEquals(1, result.networkdata().rbh());

        assertEquals(1, result.servicedata().constant());
        assertEquals(3481, result.servicedata().rbh());
        assertEquals(0, result.servicedata().sbh());
        assertEquals(0, result.servicedata().tv());
    }

    @Test
    void assertMethodsDoNotThrowExceptionsWithPlainBodies() {
        // Validate that methods called with plain bodies don't throw unexpected exceptions.
        // This also ensures reliability of the calculateFees() implementations
        final var txnBody = TransactionBody.newBuilder().build();
        assertDoesNotThrow(() -> smartContractFeeBuilder.getContractDeleteTxFeeMatrices(txnBody, signValueObj));
        assertDoesNotThrow(() -> smartContractFeeBuilder.getEthereumTransactionFeeMatrices(txnBody, signValueObj));
        assertDoesNotThrow(() -> smartContractFeeBuilder.getContractCallTxFeeMatrices(txnBody, signValueObj));
        assertDoesNotThrow(() -> smartContractFeeBuilder.getContractCreateTxFeeMatrices(txnBody, signValueObj));
        assertDoesNotThrow(() -> smartContractFeeBuilder.getContractUpdateTxFeeMatrices(
                txnBody, new com.hedera.hapi.node.base.Timestamp(0, 0), signValueObj));
    }
}
