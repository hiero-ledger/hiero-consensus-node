// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.file;

import static com.hedera.node.app.hapi.fees.test.UsageUtils.A_USAGES_MATRIX;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASE_FILEINFO_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;
import static com.hederahashgraph.api.proto.java.ResponseType.ANSWER_STATE_PROOF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.protobuf.ByteString;
import com.google.protobuf.StringValue;
import com.hedera.node.app.hapi.fees.test.KeyUtils;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.EstimatorFactory;
import com.hedera.node.app.hapi.fees.usage.QueryUsage;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hederahashgraph.api.proto.java.FileCreateTransactionBody;
import com.hederahashgraph.api.proto.java.FileGetInfoQuery;
import com.hederahashgraph.api.proto.java.FileUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FileOpsUsageTest {
    private final byte[] contents = "Pineapple and eggplant and avocado too".getBytes();
    private final long now = 1_234_567L;
    private final long expiry = 2_345_678L;
    private final long period = expiry - now;
    private final Key wacl = KeyUtils.A_KEY_LIST;
    private final String memo = "Verily, I say unto you";
    private final int numSigs = 3;
    private final int sigSize = 100;
    private final int numPayerKeys = 1;
    private final SigUsage sigUsage = new SigUsage(numSigs, sigSize, numPayerKeys);
    private final BaseTransactionMeta baseMeta = new BaseTransactionMeta(100, 0);

    private Function<ResponseType, QueryUsage> queryEstimatorFactory;
    private EstimatorFactory factory;
    private TxnUsageEstimator base;
    private QueryUsage queryBase;

    private FileCreateTransactionBody creationOp;
    private FileUpdateTransactionBody updateOp;
    private TransactionBody txn;
    private Query query;

    private final FileOpsUsage subject = new FileOpsUsage();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        base = mock(TxnUsageEstimator.class);
        given(base.get()).willReturn(A_USAGES_MATRIX);
        queryBase = mock(QueryUsage.class);
        given(queryBase.get()).willReturn(A_USAGES_MATRIX);

        factory = mock(EstimatorFactory.class);
        given(factory.get(any(), any(), any())).willReturn(base);
        queryEstimatorFactory = mock(Function.class);
        given(queryEstimatorFactory.apply(ANSWER_STATE_PROOF)).willReturn(queryBase);

        FileOpsUsage.txnEstimateFactory = factory;
        FileOpsUsage.queryEstimateFactory = queryEstimatorFactory;
    }

    @AfterEach
    void cleanup() {
        FileOpsUsage.txnEstimateFactory = TxnUsageEstimator::new;
        FileOpsUsage.queryEstimateFactory = QueryUsage::new;
    }

    @Test
    void estimatesAppendAsExpected() {
        // setup:
        final var accumulator = mock(UsageAccumulator.class);
        final int byteAdded = 1_234;
        final long lifetime = 1_234_567L;
        final var meta = new FileAppendMeta(byteAdded, lifetime);

        // when:
        subject.fileAppendUsage(sigUsage, meta, baseMeta, accumulator);

        // then:
        verify(accumulator).resetForTransaction(baseMeta, sigUsage);
        verify(accumulator).addBpt(byteAdded + (long) BASIC_ENTITY_ID_SIZE);
        verify(accumulator).addSbs(byteAdded * lifetime);
    }

    @Test
    void estimatesInfoAsExpected() {
        givenInfoOp();
        // and:
        final var ctx = ExtantFileContext.newBuilder()
                .setCurrentExpiry(expiry)
                .setCurrentMemo(memo)
                .setCurrentWacl(wacl.getKeyList())
                .setCurrentSize(contents.length)
                .build();
        // and:
        given(queryBase.get()).willReturn(A_USAGES_MATRIX);

        // when:
        final var estimate = subject.fileInfoUsage(query, ctx);

        // then:
        assertSame(A_USAGES_MATRIX, estimate);
        // and:
        verify(queryBase).addTb(BASIC_ENTITY_ID_SIZE);
        verify(queryBase).addSb(BASE_FILEINFO_SIZE + memo.length() + getAccountKeyStorageSize(wacl));
    }

    @Test
    void estimatesCreationAsExpected() {
        givenCreationOp();
        // and given:
        final long sb = reprSize();
        final long bytesUsed = reprSize() - FileOpsUsage.bytesInBaseRepr() + LONG_SIZE;

        // when:
        final var estimate = subject.fileCreateUsage(txn, sigUsage);

        // then:
        assertEquals(A_USAGES_MATRIX, estimate);
        // and:
        verify(base).addBpt(bytesUsed);
        verify(base).addSbs(sb * period);
        verify(base).addNetworkRbs(BASIC_ENTITY_ID_SIZE * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    @Test
    void estimatesUpdateAsExpected() {
        // setup:
        final long oldExpiry = expiry - 1_234L;
        final byte[] oldContents = "Archiac".getBytes();
        final KeyList oldWacl = KeyUtils.A_KEY_LIST.getKeyList();
        final String oldMemo = "Lettuce";
        // and:
        final long bytesUsed = reprSize() - FileOpsUsage.bytesInBaseRepr();
        // and:
        final long oldSbs = (oldExpiry - now)
                * (oldContents.length
                        + oldMemo.length()
                        + getAccountKeyStorageSize(
                                Key.newBuilder().setKeyList(oldWacl).build()));
        // and:
        final long newSbs = (expiry - now) * bytesUsed;

        givenUpdateOp();
        // and:
        final var ctx = ExtantFileContext.newBuilder()
                .setCurrentExpiry(oldExpiry)
                .setCurrentMemo(oldMemo)
                .setCurrentWacl(oldWacl)
                .setCurrentSize(oldContents.length)
                .build();

        // when:
        final var estimate = subject.fileUpdateUsage(txn, sigUsage, ctx);

        // then:
        assertEquals(A_USAGES_MATRIX, estimate);
        // and:
        verify(base).addBpt(bytesUsed + BASIC_ENTITY_ID_SIZE + LONG_SIZE);
        verify(base).addSbs(newSbs - oldSbs);
    }

    @Test
    void estimatesEmptyUpdateAsExpected() {
        // setup:
        final long oldExpiry = expiry - 1_234L;
        final byte[] oldContents = "Archiac".getBytes();
        final KeyList oldWacl = KeyUtils.A_KEY_LIST.getKeyList();
        final String oldMemo = "Lettuce";

        givenEmptyUpdateOp();
        // and:
        final var ctx = ExtantFileContext.newBuilder()
                .setCurrentExpiry(oldExpiry)
                .setCurrentMemo(oldMemo)
                .setCurrentWacl(oldWacl)
                .setCurrentSize(oldContents.length)
                .build();

        // when:
        final var estimate = subject.fileUpdateUsage(txn, sigUsage, ctx);

        // then:
        assertEquals(A_USAGES_MATRIX, estimate);
        // and:
        verify(base).addBpt(BASIC_ENTITY_ID_SIZE + LONG_SIZE);
        verify(base, never()).addSbs(0L);
    }

    @Test
    void hasExpectedBaseReprSize() {
        // given:
        final int expected = FeeBuilder.BOOL_SIZE + FeeBuilder.LONG_SIZE;

        // expect:
        assertEquals(expected, FileOpsUsage.bytesInBaseRepr());
    }

    private long reprSize() {
        return FileOpsUsage.bytesInBaseRepr()
                + contents.length
                + memo.length()
                + FeeBuilder.getAccountKeyStorageSize(wacl);
    }

    private void givenEmptyUpdateOp() {
        updateOp = FileUpdateTransactionBody.newBuilder()
                .setExpirationTime(Timestamp.newBuilder().setSeconds(expiry))
                .build();
        setUpdateTxn();
    }

    private void givenUpdateOp() {
        updateOp = FileUpdateTransactionBody.newBuilder()
                .setContents(ByteString.copyFrom(contents))
                .setMemo(StringValue.newBuilder().setValue(memo))
                .setExpirationTime(Timestamp.newBuilder().setSeconds(expiry))
                .setKeys(wacl.getKeyList())
                .build();
        setUpdateTxn();
    }

    private void givenInfoOp() {
        query = Query.newBuilder()
                .setFileGetInfo(FileGetInfoQuery.newBuilder()
                        .setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_STATE_PROOF)))
                .build();
    }

    private void givenCreationOp() {
        creationOp = FileCreateTransactionBody.newBuilder()
                .setContents(ByteString.copyFrom(contents))
                .setMemo(memo)
                .setKeys(wacl.getKeyList())
                .setExpirationTime(Timestamp.newBuilder().setSeconds(expiry))
                .build();
        setCreateTxn();
    }

    private void setCreateTxn() {
        txn = TransactionBody.newBuilder()
                .setTransactionID(TransactionID.newBuilder()
                        .setTransactionValidStart(Timestamp.newBuilder().setSeconds(now)))
                .setFileCreate(creationOp)
                .build();
    }

    private void setUpdateTxn() {
        txn = TransactionBody.newBuilder()
                .setTransactionID(TransactionID.newBuilder()
                        .setTransactionValidStart(Timestamp.newBuilder().setSeconds(now)))
                .setFileUpdate(updateOp)
                .build();
    }
}
