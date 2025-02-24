// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.fee;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.file.FileDeleteTransactionBody;
import com.hedera.hapi.node.file.SystemDeleteTransactionBody;
import com.hedera.hapi.node.file.SystemUndeleteTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import org.junit.jupiter.api.Test;

class FileFeeBuilderTest {
    private final TransactionBody.Builder transactionBodyBuilder =
            TransactionBody.newBuilder().memo("memo tx");
    private final SigValueObj signValueObj = new SigValueObj(2, 2, 2);
    private final FileFeeBuilder fileFeeBuilder = new FileFeeBuilder();

    @Test
    void assertGetFileContentQueryFeeMatrices() {
        var result = fileFeeBuilder.getFileContentQueryFeeMatrices(2, ResponseType.ANSWER_STATE_PROOF);
        assertEquals(1, result.nodedata().constant());
        assertEquals(236, result.nodedata().bpt());
        assertEquals(2016, result.nodedata().bpr());
        assertEquals(26, result.nodedata().sbpr());
    }

    @Test
    void assertGetSystemDeleteFileTxFeeMatrices() {
        var transactionBody = transactionBodyBuilder
                .systemDelete(SystemDeleteTransactionBody.newBuilder().build())
                .build();
        var result = fileFeeBuilder.getSystemDeleteFileTxFeeMatrices(transactionBody, signValueObj);
        assertEquals(1, result.nodedata().constant());
        assertEquals(115, result.nodedata().bpt());
        assertEquals(2, result.nodedata().vpt());
        assertEquals(1, result.networkdata().constant());
        assertEquals(115, result.networkdata().bpt());
        assertEquals(2, result.networkdata().vpt());
        assertEquals(1, result.networkdata().rbh());
        assertEquals(6, result.servicedata().rbh());
        assertEquals(1, result.servicedata().constant());
    }

    @Test
    void assertGetSystemUnDeleteFileTxFeeMatrices() {
        var transactionBody = transactionBodyBuilder
                .systemUndelete(SystemUndeleteTransactionBody.newBuilder().build())
                .build();
        var result = fileFeeBuilder.getSystemUnDeleteFileTxFeeMatrices(transactionBody, signValueObj);
        assertEquals(1, result.nodedata().constant());
        assertEquals(115, result.nodedata().bpt());
        assertEquals(2, result.nodedata().vpt());
        assertEquals(1, result.networkdata().constant());
        assertEquals(115, result.networkdata().bpt());
        assertEquals(2, result.networkdata().vpt());
        assertEquals(1, result.networkdata().rbh());
        assertEquals(6, result.servicedata().rbh());
        assertEquals(1, result.servicedata().constant());
    }

    @Test
    void assertGetFileDeleteTxFeeMatrices() {
        var transactionBody = transactionBodyBuilder
                .fileDelete(FileDeleteTransactionBody.newBuilder().build())
                .build();
        var result = fileFeeBuilder.getFileDeleteTxFeeMatrices(transactionBody, signValueObj);
        assertEquals(1, result.nodedata().constant());
        assertEquals(109, result.nodedata().bpt());
        assertEquals(2, result.nodedata().vpt());
        assertEquals(4, result.nodedata().bpr());
        assertEquals(1, result.networkdata().constant());
        assertEquals(109, result.networkdata().bpt());
        assertEquals(2, result.networkdata().vpt());
        assertEquals(1, result.networkdata().rbh());
        assertEquals(6, result.servicedata().rbh());
        assertEquals(1, result.servicedata().constant());
    }
}
