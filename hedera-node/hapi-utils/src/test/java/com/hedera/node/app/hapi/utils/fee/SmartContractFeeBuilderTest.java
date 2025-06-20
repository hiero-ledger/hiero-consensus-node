// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.fee;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hederahashgraph.api.proto.java.EthereumTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.junit.jupiter.api.Test;

class SmartContractFeeBuilderTest {
    private final TransactionBody.Builder transactionBodyBuilder =
            TransactionBody.newBuilder().setMemo("memo tx");
    private final SigValueObj signValueObj = new SigValueObj(2, 2, 2);
    private final SmartContractFeeBuilder smartContractFeeBuilder = new SmartContractFeeBuilder();

    @Test
    void assertGetFileContentQueryFeeMatrices() {
        var transactionBody = transactionBodyBuilder
                .setEthereumTransaction(EthereumTransactionBody.newBuilder())
                .build();
        var result = smartContractFeeBuilder.getEthereumTransactionFeeMatrices(transactionBody, signValueObj);
        assertEquals(1, result.getNodedata().getConstant());
        assertEquals(229, result.getNodedata().getBpt());
        assertEquals(2, result.getNodedata().getVpt());
        assertEquals(4, result.getNodedata().getBpr());
        assertEquals(0, result.getNodedata().getSbpr());

        assertEquals(1, result.getNetworkdata().getConstant());
        assertEquals(229, result.getNetworkdata().getBpt());
        assertEquals(3, result.getNetworkdata().getVpt());
        assertEquals(1, result.getNetworkdata().getRbh());

        assertEquals(1, result.getServicedata().getConstant());
        assertEquals(3481, result.getServicedata().getRbh());
        assertEquals(0, result.getServicedata().getSbh());
        assertEquals(0, result.getServicedata().getTv());
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
                txnBody, fromPbj(new com.hedera.hapi.node.base.Timestamp(0, 0)), signValueObj));
    }
}
