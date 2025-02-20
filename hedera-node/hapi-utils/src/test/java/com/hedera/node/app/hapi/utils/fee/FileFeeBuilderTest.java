// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.fee;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hederahashgraph.api.proto.java.FileDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.SystemDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.SystemUndeleteTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.junit.jupiter.api.Test;

class FileFeeBuilderTest {
    private final TransactionBody.Builder transactionBodyBuilder =
            TransactionBody.newBuilder().setMemo("memo tx");
    private final SigValueObj signValueObj = new SigValueObj(2, 2, 2);
    private final FileFeeBuilder fileFeeBuilder = new FileFeeBuilder();

    @Test
    void assertGetFileContentQueryFeeMatrices() {
        var result = fileFeeBuilder.getFileContentQueryFeeMatrices(2, ResponseType.ANSWER_STATE_PROOF);
        assertEquals(1, result.getNodedata().getConstant());
        assertEquals(236, result.getNodedata().getBpt());
        assertEquals(2016, result.getNodedata().getBpr());
        assertEquals(26, result.getNodedata().getSbpr());
    }

    @Test
    void assertGetSystemDeleteFileTxFeeMatrices() {
        var transactionBody = transactionBodyBuilder
                .setSystemDelete(SystemDeleteTransactionBody.newBuilder().build())
                .build();
        var result = fileFeeBuilder.getSystemDeleteFileTxFeeMatrices(transactionBody, signValueObj);
        assertEquals(1, result.getNodedata().getConstant());
        assertEquals(115, result.getNodedata().getBpt());
        assertEquals(2, result.getNodedata().getVpt());
        assertEquals(1, result.getNetworkdata().getConstant());
        assertEquals(115, result.getNetworkdata().getBpt());
        assertEquals(2, result.getNetworkdata().getVpt());
        assertEquals(1, result.getNetworkdata().getRbh());
        assertEquals(6, result.getServicedata().getRbh());
        assertEquals(1, result.getServicedata().getConstant());
    }

    @Test
    void assertGetSystemUnDeleteFileTxFeeMatrices() {
        var transactionBody = transactionBodyBuilder
                .setSystemUndelete(SystemUndeleteTransactionBody.newBuilder().build())
                .build();
        var result = fileFeeBuilder.getSystemUnDeleteFileTxFeeMatrices(transactionBody, signValueObj);
        assertEquals(1, result.getNodedata().getConstant());
        assertEquals(115, result.getNodedata().getBpt());
        assertEquals(2, result.getNodedata().getVpt());
        assertEquals(1, result.getNetworkdata().getConstant());
        assertEquals(115, result.getNetworkdata().getBpt());
        assertEquals(2, result.getNetworkdata().getVpt());
        assertEquals(1, result.getNetworkdata().getRbh());
        assertEquals(6, result.getServicedata().getRbh());
        assertEquals(1, result.getServicedata().getConstant());
    }

    @Test
    void assertGetFileDeleteTxFeeMatrices() {
        var transactionBody = transactionBodyBuilder
                .setFileDelete(FileDeleteTransactionBody.newBuilder().build())
                .build();
        var result = fileFeeBuilder.getFileDeleteTxFeeMatrices(transactionBody, signValueObj);
        assertEquals(1, result.getNodedata().getConstant());
        assertEquals(109, result.getNodedata().getBpt());
        assertEquals(2, result.getNodedata().getVpt());
        assertEquals(4, result.getNodedata().getBpr());
        assertEquals(1, result.getNetworkdata().getConstant());
        assertEquals(109, result.getNetworkdata().getBpt());
        assertEquals(2, result.getNetworkdata().getVpt());
        assertEquals(1, result.getNetworkdata().getRbh());
        assertEquals(6, result.getServicedata().getRbh());
        assertEquals(1, result.getServicedata().getConstant());
    }
}
