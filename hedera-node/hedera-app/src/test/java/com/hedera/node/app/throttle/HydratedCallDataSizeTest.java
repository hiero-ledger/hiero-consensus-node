// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HydratedCallDataSizeTest {
    private static final long FIRST_USER_ENTITY = 1001L;

    // A valid EIP-1559 EthereumTransaction with a to-address and *empty* inline callData; hydration applies
    // when it also carries a callData FileID. (Same fixture the hydration tests use.)
    private static final Bytes ETH_WITH_TO_ADDRESS = Bytes.fromHex(
            "02f8ad82012a80a000000000000000000000000000000000000000000000000000000000000003e8a0000000000000000000000000000000000000000000000000000000746a528800831e848094fee687d5088faff48013a6767505c027e2742536880de0b6b3a764000080c080a0f5ddf2394311e634e2147bf38583a017af45f4326bdf5746cac3a1110f973e4fa025bad52d9a9f8b32eb983c9fb8959655258bd75e2826b2c6a48d4c26ec30d112");
    // A legacy EthereumTransaction that already carries inline callData (so no hydration occurs).
    private static final Bytes ETH_WITH_INLINE_CALL_DATA = Bytes.fromHex(
            "f864012f83018000947e3a9eaf9bcc39e2ffa38eb30bf7a93feacbc18180827653820277a0f9fbff985d374be4a55f296915002eec11ac96f1ce2df183adf992baa9390b2fa00c1e867cc960d9c74ec2e6a662b7908ec4c8cc9f3091e886bcefbeb2290fb792");
    private static final FileID CALL_DATA_FILE_ID =
            FileID.newBuilder().fileNum(7890L).build();

    @Mock
    private ReadableFileStore fileStore;

    @Test
    void returnsDecodedCallDataSizeForFileReferencedTxn() {
        final var decoded = new byte[100];
        givenFileContents(HexFormat.of().formatHex(decoded));
        assertEquals(100L, sizeFor(fileReferenced()));
    }

    @Test
    void stripsLeadingHexPrefixWhenPresent() {
        final var decoded = new byte[100];
        givenFileContents("0x" + HexFormat.of().formatHex(decoded));
        assertEquals(100L, sizeFor(fileReferenced()));
    }

    @Test
    void returnsZeroWhenInlineCallDataIsPresent() {
        final var body = ethBody(EthereumTransactionBody.newBuilder()
                .ethereumData(ETH_WITH_INLINE_CALL_DATA)
                .callData(CALL_DATA_FILE_ID)
                .build());
        assertEquals(0L, sizeFor(body));
        verifyNoInteractions(fileStore);
    }

    @Test
    void returnsZeroWhenNoCallDataFileIsReferenced() {
        final var body = ethBody(EthereumTransactionBody.newBuilder()
                .ethereumData(ETH_WITH_TO_ADDRESS)
                .build());
        assertEquals(0L, sizeFor(body));
        verifyNoInteractions(fileStore);
    }

    @Test
    void returnsZeroForNonEthereumTransaction() {
        assertEquals(0L, sizeFor(TransactionBody.newBuilder().build()));
        verifyNoInteractions(fileStore);
    }

    @Test
    void returnsZeroForSystemCallDataFile() {
        final var body = ethBody(EthereumTransactionBody.newBuilder()
                .ethereumData(ETH_WITH_TO_ADDRESS)
                .callData(FileID.newBuilder().fileNum(150L).build())
                .build());
        assertEquals(0L, sizeFor(body));
        verifyNoInteractions(fileStore);
    }

    @Test
    void returnsZeroWhenFileIsMissing() {
        given(fileStore.getFileLeaf(CALL_DATA_FILE_ID)).willReturn(null);
        assertEquals(0L, sizeFor(fileReferenced()));
    }

    @Test
    void returnsZeroWhenFileIsDeleted() {
        given(fileStore.getFileLeaf(CALL_DATA_FILE_ID))
                .willReturn(File.newBuilder().deleted(true).build());
        assertEquals(0L, sizeFor(fileReferenced()));
    }

    @Test
    void returnsZeroWhenFileIsEmpty() {
        given(fileStore.getFileLeaf(CALL_DATA_FILE_ID))
                .willReturn(File.newBuilder().contents(Bytes.EMPTY).build());
        assertEquals(0L, sizeFor(fileReferenced()));
    }

    private long sizeFor(final TransactionBody body) {
        return HydratedCallDataSize.fileReferencedCallDataBytes(body, fileStore, FIRST_USER_ENTITY);
    }

    private TransactionBody fileReferenced() {
        return ethBody(EthereumTransactionBody.newBuilder()
                .ethereumData(ETH_WITH_TO_ADDRESS)
                .callData(CALL_DATA_FILE_ID)
                .build());
    }

    private void givenFileContents(final String hex) {
        given(fileStore.getFileLeaf(CALL_DATA_FILE_ID))
                .willReturn(File.newBuilder().contents(Bytes.wrap(hex)).build());
    }

    private static TransactionBody ethBody(final EthereumTransactionBody ethTxn) {
        return TransactionBody.newBuilder().ethereumTransaction(ethTxn).build();
    }
}
