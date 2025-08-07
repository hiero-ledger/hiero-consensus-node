// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.nfra;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_FILE_EMPTY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FILE_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hedera.node.app.service.contract.impl.hevm.HydratedEthTxData.successFrom;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALL_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETH_CALLDATA_FILE_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETH_DATA_WITH_CALL_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETH_DATA_WITH_TO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETH_WITH_CALL_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETH_WITH_TO_ADDRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule;
import com.hedera.node.app.service.contract.impl.infra.EthereumCallDataHydration;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EthereumCallDataHydrationTest {
    @Mock
    private ReadableFileStore fileStore;

    private EthereumCallDataHydration subject = new EthereumCallDataHydration();

    @Test
    void failsWithInvalidEthTxWithInvalidData() {
        final var ethTxn =
                EthereumTransactionBody.newBuilder().ethereumData(Bytes.EMPTY).build();
        final var result = subject.tryToHydrate(ethTxn, fileStore, 1001L);
        assertFalse(result.isAvailable());
        assertEquals(INVALID_ETHEREUM_TRANSACTION, result.status());
    }

    @Test
    void doesNoHydrationIfCallDataFileNotSet() {
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(ETH_WITH_TO_ADDRESS)
                .build();
        assertEquals(successFrom(ETH_DATA_WITH_TO_ADDRESS, false), subject.tryToHydrate(ethTxn, fileStore, 1001L));
        verifyNoInteractions(fileStore);
    }

    @Test
    void doesNoHydrationIfCallDataAlreadyAvailable() {
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(ETH_WITH_CALL_DATA)
                .callData(ETH_CALLDATA_FILE_ID)
                .build();
        assertEquals(successFrom(ETH_DATA_WITH_CALL_DATA, false), subject.tryToHydrate(ethTxn, fileStore, 1001L));
        verifyNoInteractions(fileStore);
    }

    @Test
    void failsWithInvalidFileIdOnSystemInitcodeId() {
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(ETH_WITH_TO_ADDRESS)
                .callData(FileID.newBuilder().fileNum(ProcessorModule.NUM_SYSTEM_ACCOUNTS))
                .build();
        final var result = subject.tryToHydrate(ethTxn, fileStore, 1001L);
        assertFalse(result.isAvailable());
        assertEquals(INVALID_FILE_ID, result.status());
        verifyNoInteractions(fileStore);
    }

    @Test
    void failsWithInvalidFileIdOnMissingCallDataFile() {
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(ETH_WITH_TO_ADDRESS)
                .callData(ETH_CALLDATA_FILE_ID)
                .build();
        final var result = subject.tryToHydrate(ethTxn, fileStore, 1001L);
        assertFalse(result.isAvailable());
        assertEquals(INVALID_FILE_ID, result.status());
    }

    @Test
    void failsWithFileDeletedOnMissingCallDataFile() {
        given(fileStore.getFileLeaf(ETH_CALLDATA_FILE_ID))
                .willReturn(File.newBuilder().deleted(true).build());
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(ETH_WITH_TO_ADDRESS)
                .callData(ETH_CALLDATA_FILE_ID)
                .build();
        final var result = subject.tryToHydrate(ethTxn, fileStore, 1001L);
        assertFalse(result.isAvailable());
        assertEquals(FILE_DELETED, result.status());
    }

    @Test
    void failsWithInvalidFileIdOnUnparseableCallDataFile() {
        given(fileStore.getFileLeaf(ETH_CALLDATA_FILE_ID))
                .willReturn(File.newBuilder().contents(Bytes.wrap("xyz")).build());
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(ETH_WITH_TO_ADDRESS)
                .callData(ETH_CALLDATA_FILE_ID)
                .build();
        final var result = subject.tryToHydrate(ethTxn, fileStore, 1001L);
        assertFalse(result.isAvailable());
        assertEquals(INVALID_FILE_ID, result.status());
    }

    @Test
    void failsWithInvalidFileIdOnEmptyCallDataFile() {
        given(fileStore.getFileLeaf(ETH_CALLDATA_FILE_ID))
                .willReturn(File.newBuilder().contents(Bytes.EMPTY).build());
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(ETH_WITH_TO_ADDRESS)
                .callData(ETH_CALLDATA_FILE_ID)
                .build();
        final var result = subject.tryToHydrate(ethTxn, fileStore, 1001L);
        assertFalse(result.isAvailable());
        assertEquals(CONTRACT_FILE_EMPTY, result.status());
    }

    @Test
    void replacesCallDataIfAppropriate() {
        final var hexedCallData = Hex.encode(CALL_DATA.toByteArray());
        final var expectedData = ETH_DATA_WITH_TO_ADDRESS.replaceCallData(CALL_DATA.toByteArray());
        given(fileStore.getFileLeaf(ETH_CALLDATA_FILE_ID))
                .willReturn(
                        File.newBuilder().contents(Bytes.wrap(hexedCallData)).build());
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(ETH_WITH_TO_ADDRESS)
                .callData(ETH_CALLDATA_FILE_ID)
                .build();
        final var result = subject.tryToHydrate(ethTxn, fileStore, 1001L);
        assertTrue(result.isAvailable());
        assertEquals(expectedData, result.ethTxData());
    }
}
