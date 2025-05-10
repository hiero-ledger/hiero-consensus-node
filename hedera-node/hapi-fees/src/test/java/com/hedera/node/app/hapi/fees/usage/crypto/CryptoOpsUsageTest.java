// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static com.hedera.node.app.hapi.fees.test.UsageUtils.A_USAGES_MATRIX;
import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoContextUtils.countSerials;
import static com.hedera.node.app.hapi.fees.usage.crypto.CryptoDeleteAllowanceMeta.countNftDeleteSerials;
import static com.hedera.node.app.hapi.fees.usage.crypto.entities.CryptoEntitySizes.CRYPTO_ENTITY_SIZES;
import static com.hedera.node.app.hapi.fees.usage.token.entities.TokenEntitySizes.TOKEN_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BOOL_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.CRYPTO_ALLOWANCE_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.INT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.NFT_ALLOWANCE_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.NFT_DELETE_ALLOWANCE_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.TOKEN_ALLOWANCE_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;
import static com.hederahashgraph.api.proto.java.ResponseType.ANSWER_STATE_PROOF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.protobuf.ByteString;
import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import com.hedera.node.app.hapi.fees.test.IdUtils;
import com.hedera.node.app.hapi.fees.test.KeyUtils;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.EstimatorFactory;
import com.hedera.node.app.hapi.fees.usage.QueryUsage;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.fees.usage.file.FileOpsUsage;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoAllowance;
import com.hederahashgraph.api.proto.java.CryptoApproveAllowanceTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoDeleteAllowanceTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoGetInfoQuery;
import com.hederahashgraph.api.proto.java.CryptoUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.NftAllowance;
import com.hederahashgraph.api.proto.java.NftRemoveAllowance;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignaturePair;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenAllowance;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CryptoOpsUsageTest {
    private final int numTokenRels = 3;
    private final long secs = 500_000L;
    private final long now = 1_234_567L;
    private final long expiry = now + secs;
    private final Key key = KeyUtils.A_COMPLEX_KEY;
    private final String memo = "That abler soul, which thence doth flow";
    private final AccountID proxy = IdUtils.asAccount("0.0.75231");
    private final AccountID proxy2 = IdUtils.asAccount("0.0.75232");
    private final AccountID proxy3 = IdUtils.asAccount("0.0.75233");
    private final AccountID owner = IdUtils.asAccount("0.0.10000");
    private final int maxAutoAssociations = 123;
    private final int numSigs = 3;
    private final int sigSize = 100;
    private final int numPayerKeys = 1;
    private final CryptoAllowance cryptoAllowances =
            CryptoAllowance.newBuilder().setSpender(proxy).setAmount(10L).build();
    private final TokenAllowance tokenAllowances = TokenAllowance.newBuilder()
            .setSpender(proxy)
            .setAmount(10L)
            .setTokenId(IdUtils.asToken("0.0.1000"))
            .build();
    private final NftAllowance nftAllowances = NftAllowance.newBuilder()
            .setSpender(proxy)
            .setTokenId(IdUtils.asToken("0.0.1000"))
            .addAllSerialNumbers(List.of(1L))
            .build();

    private final NftRemoveAllowance nftDeleteAllowances = NftRemoveAllowance.newBuilder()
            .setOwner(proxy)
            .setTokenId(IdUtils.asToken("0.0.1000"))
            .addAllSerialNumbers(List.of(1L))
            .build();

    private final SigUsage sigUsage = new SigUsage(numSigs, sigSize, numPayerKeys);

    private EstimatorFactory factory;
    private TxnUsageEstimator base;
    private Function<ResponseType, QueryUsage> queryEstimatorFactory;
    private QueryUsage queryBase;

    private CryptoCreateTransactionBody creationOp;
    private CryptoUpdateTransactionBody updateOp;
    private CryptoApproveAllowanceTransactionBody approveOp;
    private CryptoDeleteAllowanceTransactionBody deleteAllowanceOp;
    private TransactionBody txn;
    private Query query;

    private final CryptoOpsUsage subject = new CryptoOpsUsage();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        base = mock(TxnUsageEstimator.class);
        given(base.get()).willReturn(A_USAGES_MATRIX);
        queryBase = mock(QueryUsage.class);
        given(queryBase.get()).willReturn(A_USAGES_MATRIX);

        factory = mock(EstimatorFactory.class);
        given(factory.get(any(), any(), any())).willReturn(base);
        queryEstimatorFactory = mock(Function.class);
        given(queryEstimatorFactory.apply(ANSWER_STATE_PROOF)).willReturn(queryBase);

        CryptoOpsUsage.txnEstimateFactory = factory;
        CryptoOpsUsage.queryEstimateFactory = queryEstimatorFactory;
    }

    @AfterEach
    void cleanup() {
        CryptoOpsUsage.txnEstimateFactory = TxnUsageEstimator::new;
        CryptoOpsUsage.queryEstimateFactory = QueryUsage::new;
    }

    @Test
    void estimatesInfoAsExpected() {
        givenInfoOp();
        // and:
        final var ctx = ExtantCryptoContext.newBuilder()
                .setCurrentExpiry(expiry)
                .setCurrentMemo(memo)
                .setCurrentKey(key)
                .setCurrentlyHasProxy(true)
                .setCurrentNumTokenRels(numTokenRels)
                .setCurrentMaxAutomaticAssociations(maxAutoAssociations)
                .setCurrentCryptoAllowances(Collections.emptyMap())
                .setCurrentApproveForAllNftAllowances(Collections.emptySet())
                .setCurrentTokenAllowances(Collections.emptyMap())
                .build();
        // and:
        given(queryBase.get()).willReturn(A_USAGES_MATRIX);

        // when:
        final var estimate = subject.cryptoInfoUsage(query, ctx);

        // then:
        assertSame(A_USAGES_MATRIX, estimate);
        // and:
        verify(queryBase).addTb(BASIC_ENTITY_ID_SIZE);
        verify(queryBase)
                .addRb(CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr()
                        + BASIC_ENTITY_ID_SIZE
                        + memo.length()
                        + getAccountKeyStorageSize(key)
                        + numTokenRels * TOKEN_ENTITY_SIZES.bytesUsedPerAccountRelationship());
    }

    @Test
    void accumulatesBptAndRbhAsExpectedForCryptoCreateWithMaxAutoAssociations() {
        givenCreationOpWithMaxAutoAssociaitons();
        final ByteString canonicalSig =
                ByteString.copyFromUtf8("0123456789012345678901234567890123456789012345678901234567890123");
        final SignatureMap onePairSigMap = SignatureMap.newBuilder()
                .addSigPair(SignaturePair.newBuilder()
                        .setPubKeyPrefix(ByteString.copyFromUtf8("a"))
                        .setEd25519(canonicalSig))
                .build();
        final SigUsage singleSigUsage = new SigUsage(1, onePairSigMap.getSerializedSize(), 1);
        final var opMeta = new CryptoCreateMeta(txn.getCryptoCreateAccount());
        final var baseMeta = new BaseTransactionMeta(memo.length(), 0);

        final var actual = new UsageAccumulator();
        final var expected = new UsageAccumulator();

        final var baseSize = memo.length() + getAccountKeyStorageSize(key) + BASIC_ENTITY_ID_SIZE + INT_SIZE;
        expected.resetForTransaction(baseMeta, singleSigUsage);
        expected.addBpt(baseSize + 2 * LONG_SIZE + BOOL_SIZE);
        expected.addRbs((CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr() + baseSize) * secs);
        expected.addNetworkRbs(BASIC_ENTITY_ID_SIZE * USAGE_PROPERTIES.legacyReceiptStorageSecs());

        subject.cryptoCreateUsage(singleSigUsage, baseMeta, opMeta, actual);

        assertEquals(expected, actual);
    }

    @Test
    void accumulatesBptAndRbhAsExpectedForCryptoCreateWithoutMaxAutoAssociations() {
        givenCreationOpWithOutMaxAutoAssociaitons();
        final ByteString canonicalSig =
                ByteString.copyFromUtf8("0123456789012345678901234567890123456789012345678901234567890123");
        final SignatureMap onePairSigMap = SignatureMap.newBuilder()
                .addSigPair(SignaturePair.newBuilder()
                        .setPubKeyPrefix(ByteString.copyFromUtf8("a"))
                        .setEd25519(canonicalSig))
                .build();
        final SigUsage singleSigUsage = new SigUsage(1, onePairSigMap.getSerializedSize(), 1);
        final var opMeta = new CryptoCreateMeta(txn.getCryptoCreateAccount());
        final var baseMeta = new BaseTransactionMeta(memo.length(), 0);

        final var actual = new UsageAccumulator();
        final var expected = new UsageAccumulator();

        final var baseSize = memo.length() + getAccountKeyStorageSize(key) + BASIC_ENTITY_ID_SIZE;
        expected.resetForTransaction(baseMeta, singleSigUsage);
        expected.addBpt(baseSize + 2 * LONG_SIZE + BOOL_SIZE);
        expected.addRbs((CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr() + baseSize) * secs);
        expected.addNetworkRbs(BASIC_ENTITY_ID_SIZE * USAGE_PROPERTIES.legacyReceiptStorageSecs());

        subject.cryptoCreateUsage(singleSigUsage, baseMeta, opMeta, actual);

        assertEquals(expected, actual);
    }

    @Test
    void estimatesAutoRenewAsExpected() {
        final var expectedRbsUsedInRenewal =
                (basicReprBytes() + (numTokenRels * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr()));

        final var ctx = ExtantCryptoContext.newBuilder()
                .setCurrentExpiry(expiry)
                .setCurrentMemo(memo)
                .setCurrentKey(key)
                .setCurrentlyHasProxy(true)
                .setCurrentNumTokenRels(numTokenRels)
                .setCurrentMaxAutomaticAssociations(maxAutoAssociations)
                .setCurrentCryptoAllowances(Collections.emptyMap())
                .setCurrentApproveForAllNftAllowances(Collections.emptySet())
                .setCurrentTokenAllowances(Collections.emptyMap())
                .build();

        final var estimate = subject.cryptoAutoRenewRb(ctx);

        assertEquals(expectedRbsUsedInRenewal, estimate);
    }

    @Test
    void estimatesUpdateWithAutoAssociationsAsExpectedWhenNoExplicitAutoAssocSlotLifetime() {
        givenUpdateOpWithMaxAutoAssociations();
        final var expected = new UsageAccumulator();
        final var baseMeta = new BaseTransactionMeta(memo.length(), 0);
        final var opMeta = new CryptoUpdateMeta(
                txn.getCryptoUpdateAccount(),
                txn.getTransactionID().getTransactionValidStart().getSeconds());

        expected.resetForTransaction(baseMeta, sigUsage);

        final Key oldKey = FileOpsUsage.asKey(KeyUtils.A_KEY_LIST.getKeyList());
        final long oldExpiry = expiry - 1_234L;
        final String oldMemo = "Lettuce";
        final int oldMaxAutoAssociations = maxAutoAssociations - 5;

        final var ctx = ExtantCryptoContext.newBuilder()
                .setCurrentExpiry(oldExpiry)
                .setCurrentMemo(oldMemo)
                .setCurrentKey(oldKey)
                .setCurrentlyHasProxy(false)
                .setCurrentNumTokenRels(numTokenRels)
                .setCurrentMaxAutomaticAssociations(oldMaxAutoAssociations)
                .setCurrentCryptoAllowances(Collections.emptyMap())
                .setCurrentApproveForAllNftAllowances(Collections.emptySet())
                .setCurrentTokenAllowances(Collections.emptyMap())
                .build();

        final long keyBytesUsed = getAccountKeyStorageSize(key);
        final long msgBytesUsed = BASIC_ENTITY_ID_SIZE
                + memo.getBytes().length
                + keyBytesUsed
                + LONG_SIZE
                + BASIC_ENTITY_ID_SIZE
                + INT_SIZE;

        expected.addBpt(msgBytesUsed);

        final long newVariableBytes = memo.getBytes().length + keyBytesUsed + BASIC_ENTITY_ID_SIZE;
        final long tokenRelBytes = numTokenRels * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr();
        final long sharedFixedBytes = CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr() + tokenRelBytes;
        final long newLifetime = ESTIMATOR_UTILS.relativeLifetime(txn, expiry);
        final long oldLifetime = ESTIMATOR_UTILS.relativeLifetime(txn, oldExpiry);
        final long rbsDelta = ESTIMATOR_UTILS.changeInBsUsage(
                CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr()
                        + ctx.currentNonBaseRb()
                        + ctx.currentNumTokenRels() * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr(),
                oldLifetime,
                sharedFixedBytes + newVariableBytes,
                newLifetime);
        if (rbsDelta > 0) {
            expected.addRbs(rbsDelta);
        }

        final var slotDelta = ESTIMATOR_UTILS.changeInBsUsage(
                oldMaxAutoAssociations * CryptoOpsUsage.UPDATE_SLOT_MULTIPLIER,
                oldLifetime,
                maxAutoAssociations * CryptoOpsUsage.UPDATE_SLOT_MULTIPLIER,
                newLifetime);
        expected.addRbs(slotDelta);

        final var actual = new UsageAccumulator();

        subject.cryptoUpdateUsage(sigUsage, baseMeta, opMeta, ctx, actual, 0);

        assertEquals(expected, actual);
    }

    @Test
    void estimatesUpdateWithAutoAssociationsAsExpectedWhenGivenExplicitAutoAssocSlotLifetime() {
        givenUpdateOpWithMaxAutoAssociations();
        final var expected = new UsageAccumulator();
        final var baseMeta = new BaseTransactionMeta(memo.length(), 0);
        final var opMeta = new CryptoUpdateMeta(
                txn.getCryptoUpdateAccount(),
                txn.getTransactionID().getTransactionValidStart().getSeconds());

        expected.resetForTransaction(baseMeta, sigUsage);

        final Key oldKey = FileOpsUsage.asKey(KeyUtils.A_KEY_LIST.getKeyList());
        final long oldExpiry = expiry - 1_234L;
        final String oldMemo = "Lettuce";
        final int oldMaxAutoAssociations = maxAutoAssociations - 5;

        final var ctx = ExtantCryptoContext.newBuilder()
                .setCurrentExpiry(oldExpiry)
                .setCurrentMemo(oldMemo)
                .setCurrentKey(oldKey)
                .setCurrentlyHasProxy(false)
                .setCurrentNumTokenRels(numTokenRels)
                .setCurrentMaxAutomaticAssociations(oldMaxAutoAssociations)
                .setCurrentCryptoAllowances(Collections.emptyMap())
                .setCurrentApproveForAllNftAllowances(Collections.emptySet())
                .setCurrentTokenAllowances(Collections.emptyMap())
                .build();

        final long keyBytesUsed = getAccountKeyStorageSize(key);
        final long msgBytesUsed = BASIC_ENTITY_ID_SIZE
                + memo.getBytes().length
                + keyBytesUsed
                + LONG_SIZE
                + BASIC_ENTITY_ID_SIZE
                + INT_SIZE;

        expected.addBpt(msgBytesUsed);

        final long newVariableBytes = memo.getBytes().length + keyBytesUsed + BASIC_ENTITY_ID_SIZE;
        final long tokenRelBytes = numTokenRels * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr();
        final long sharedFixedBytes = CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr() + tokenRelBytes;
        final long newLifetime = ESTIMATOR_UTILS.relativeLifetime(txn, expiry);
        final long oldLifetime = ESTIMATOR_UTILS.relativeLifetime(txn, oldExpiry);
        final long rbsDelta = ESTIMATOR_UTILS.changeInBsUsage(
                CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr()
                        + ctx.currentNonBaseRb()
                        + ctx.currentNumTokenRels() * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr(),
                oldLifetime,
                sharedFixedBytes + newVariableBytes,
                newLifetime);
        if (rbsDelta > 0) {
            expected.addRbs(rbsDelta);
        }

        final var explicitAutoAssocSlotLifetime = 123_456_789L;
        final var slotDelta = ESTIMATOR_UTILS.changeInBsUsage(
                oldMaxAutoAssociations * CryptoOpsUsage.UPDATE_SLOT_MULTIPLIER,
                explicitAutoAssocSlotLifetime,
                maxAutoAssociations * CryptoOpsUsage.UPDATE_SLOT_MULTIPLIER,
                explicitAutoAssocSlotLifetime);
        expected.addRbs(slotDelta);

        final var actual = new UsageAccumulator();

        subject.cryptoUpdateUsage(sigUsage, baseMeta, opMeta, ctx, actual, explicitAutoAssocSlotLifetime);

        assertEquals(expected, actual);
    }

    @Test
    void estimatesUpdateWithOutAutoAssociationsAsExpected() {
        givenUpdateOpWithOutMaxAutoAssociations();
        final var expected = new UsageAccumulator();
        final var baseMeta = new BaseTransactionMeta(memo.length(), 0);
        final var opMeta = new CryptoUpdateMeta(
                txn.getCryptoUpdateAccount(),
                txn.getTransactionID().getTransactionValidStart().getSeconds());

        expected.resetForTransaction(baseMeta, sigUsage);

        final Key oldKey = FileOpsUsage.asKey(KeyUtils.A_KEY_LIST.getKeyList());
        final long oldExpiry = expiry - 1_234L;
        final String oldMemo = "Lettuce";

        final var ctx = ExtantCryptoContext.newBuilder()
                .setCurrentExpiry(oldExpiry)
                .setCurrentMemo(oldMemo)
                .setCurrentKey(oldKey)
                .setCurrentlyHasProxy(false)
                .setCurrentNumTokenRels(numTokenRels)
                .setCurrentMaxAutomaticAssociations(maxAutoAssociations)
                .setCurrentCryptoAllowances(Collections.emptyMap())
                .setCurrentApproveForAllNftAllowances(Collections.emptySet())
                .setCurrentTokenAllowances(Collections.emptyMap())
                .build();

        final long keyBytesUsed = getAccountKeyStorageSize(key);
        final long msgBytesUsed =
                BASIC_ENTITY_ID_SIZE + memo.getBytes().length + keyBytesUsed + LONG_SIZE + BASIC_ENTITY_ID_SIZE;

        expected.addBpt(msgBytesUsed);

        final long newVariableBytes = memo.getBytes().length + keyBytesUsed + BASIC_ENTITY_ID_SIZE;
        final long tokenRelBytes = numTokenRels * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr();
        final long sharedFixedBytes = CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr() + tokenRelBytes;
        final long newLifetime = ESTIMATOR_UTILS.relativeLifetime(txn, expiry);
        final long oldLifetime = ESTIMATOR_UTILS.relativeLifetime(txn, oldExpiry);
        final long rbsDelta = ESTIMATOR_UTILS.changeInBsUsage(
                CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr()
                        + ctx.currentNonBaseRb()
                        + ctx.currentNumTokenRels() * CRYPTO_ENTITY_SIZES.bytesInTokenAssocRepr(),
                oldLifetime,
                sharedFixedBytes + newVariableBytes,
                newLifetime);
        if (rbsDelta > 0) {
            expected.addRbs(rbsDelta);
        }
        final var slotDelta = ESTIMATOR_UTILS.changeInBsUsage(
                maxAutoAssociations * CryptoOpsUsage.UPDATE_SLOT_MULTIPLIER,
                oldLifetime,
                maxAutoAssociations * CryptoOpsUsage.UPDATE_SLOT_MULTIPLIER,
                newLifetime);
        expected.addRbs(slotDelta);

        final var actual = new UsageAccumulator();

        subject.cryptoUpdateUsage(sigUsage, baseMeta, opMeta, ctx, actual, 0);

        assertEquals(expected, actual);
    }

    void verifyApprovalCosts(TransactionBody txn, CryptoApproveAllowanceTransactionBody approveOp) {
        final var expected = new UsageAccumulator();
        final var baseMeta = new BaseTransactionMeta(0, 0);
        final var opMeta = new CryptoApproveAllowanceMeta(
                txn.getCryptoApproveAllowance(),
                txn.getTransactionID().getTransactionValidStart().getSeconds());
        final SigUsage sigUsage = new SigUsage(1, sigSize, 1);
        expected.resetForTransaction(baseMeta, sigUsage);

        final Key oldKey = FileOpsUsage.asKey(KeyUtils.A_KEY_LIST.getKeyList());
        final long oldExpiry = expiry - 1_234L;
        final String oldMemo = "Lettuce";

        final var ctx = ExtantCryptoContext.newBuilder()
                .setCurrentExpiry(oldExpiry)
                .setCurrentMemo(oldMemo)
                .setCurrentKey(oldKey)
                .setCurrentlyHasProxy(false)
                .setCurrentNumTokenRels(numTokenRels)
                .setCurrentMaxAutomaticAssociations(maxAutoAssociations)
                .setCurrentCryptoAllowances(Collections.emptyMap())
                .setCurrentApproveForAllNftAllowances(Collections.emptySet())
                .setCurrentTokenAllowances(Collections.emptyMap())
                .build();

        final long msgBytesUsed = (approveOp.getCryptoAllowancesCount() * CRYPTO_ALLOWANCE_SIZE)
                + (approveOp.getTokenAllowancesCount() * TOKEN_ALLOWANCE_SIZE)
                + (approveOp.getNftAllowancesCount() * NFT_ALLOWANCE_SIZE)
                + countSerials(approveOp.getNftAllowancesList()) * LONG_SIZE;

        expected.addBpt(msgBytesUsed);
        final long lifetime = ESTIMATOR_UTILS.relativeLifetime(txn, oldExpiry);
        final var expectedBytes = (approveOp.getCryptoAllowancesCount() * CRYPTO_ALLOWANCE_SIZE)
                + (approveOp.getTokenAllowancesCount() * TOKEN_ALLOWANCE_SIZE)
                + (approveOp.getNftAllowancesCount() * NFT_ALLOWANCE_SIZE);

        expected.addRbs(expectedBytes * lifetime);

        final var actual = new UsageAccumulator();

        subject.cryptoApproveAllowanceUsage(sigUsage, baseMeta, opMeta, ctx, actual);

        assertEquals(expected, actual);
    }

    @Test
    void calculateMultipleApprovalsWithTheSameSpender() {
        var approveOp = CryptoApproveAllowanceTransactionBody.newBuilder()
                // same spender
                .addAllCryptoAllowances(List.of(
                        CryptoAllowance.newBuilder()
                                .setSpender(proxy)
                                .setAmount(10L)
                                .build(),
                        CryptoAllowance.newBuilder()
                                .setSpender(proxy)
                                .setAmount(20L)
                                .build(),
                        CryptoAllowance.newBuilder()
                                .setSpender(proxy)
                                .setAmount(30L)
                                .build()))
                .addAllTokenAllowances(List.of(tokenAllowances))
                .addAllNftAllowances(List.of(nftAllowances))
                .build();
        var txn = makeApproveTxn(approveOp);
        verifyApprovalCosts(txn, approveOp);
    }

    @Test
    void calculateMultipleApprovalsWithDifferentSpenders() {
        var approveOp = CryptoApproveAllowanceTransactionBody.newBuilder()
                // diff spenders
                .addAllCryptoAllowances(List.of(
                        CryptoAllowance.newBuilder()
                                .setSpender(proxy)
                                .setAmount(10L)
                                .build(),
                        CryptoAllowance.newBuilder()
                                .setSpender(proxy2)
                                .setAmount(20L)
                                .build(),
                        CryptoAllowance.newBuilder()
                                .setSpender(proxy3)
                                .setAmount(30L)
                                .build()))
                .addAllTokenAllowances(List.of(tokenAllowances))
                .addAllNftAllowances(List.of(nftAllowances))
                .build();
        var txn = makeApproveTxn(approveOp);
        verifyApprovalCosts(txn, approveOp);
    }

    @Test
    void estimatesDeleteAsExpected() {
        givenDeleteOp();

        final var expected = new UsageAccumulator();
        final var baseMeta = new BaseTransactionMeta(0, 0);
        final var opMeta = new CryptoDeleteAllowanceMeta(
                txn.getCryptoDeleteAllowance(),
                txn.getTransactionID().getTransactionValidStart().getSeconds());
        final SigUsage sigUsage = new SigUsage(1, sigSize, 1);
        expected.resetForTransaction(baseMeta, sigUsage);

        final long msgBytesUsed = (deleteAllowanceOp.getNftAllowancesCount() * NFT_DELETE_ALLOWANCE_SIZE)
                + countNftDeleteSerials(deleteAllowanceOp.getNftAllowancesList()) * LONG_SIZE;

        expected.addBpt(msgBytesUsed);

        final var actual = new UsageAccumulator();

        subject.cryptoDeleteAllowanceUsage(sigUsage, baseMeta, opMeta, actual);

        assertEquals(expected, actual);
    }

    private long basicReprBytes() {
        return CRYPTO_ENTITY_SIZES.fixedBytesInAccountRepr()
                /* The proxy account */
                + BASIC_ENTITY_ID_SIZE
                + memo.length()
                + FeeBuilder.getAccountKeyStorageSize(key)
                + (maxAutoAssociations != 0 ? INT_SIZE : 0);
    }

    private void givenUpdateOpWithOutMaxAutoAssociations() {
        updateOp = CryptoUpdateTransactionBody.newBuilder()
                .setExpirationTime(Timestamp.newBuilder().setSeconds(expiry))
                .setProxyAccountID(proxy)
                .setMemo(StringValue.newBuilder().setValue(memo))
                .setKey(key)
                .build();
        setUpdateTxn();
    }

    private void givenDeleteOp() {
        deleteAllowanceOp = CryptoDeleteAllowanceTransactionBody.newBuilder()
                .addAllNftAllowances(List.of(nftDeleteAllowances))
                .build();
        setDeleteAllowanceTxn();
    }

    private void givenApprovalOp() {
        approveOp = CryptoApproveAllowanceTransactionBody.newBuilder()
                .addAllCryptoAllowances(List.of(cryptoAllowances))
                .addAllTokenAllowances(List.of(tokenAllowances))
                .addAllNftAllowances(List.of(nftAllowances))
                .build();
        setApproveTxn();
    }

    private void setApproveTxn() {
        txn = TransactionBody.newBuilder()
                .setTransactionID(TransactionID.newBuilder()
                        .setTransactionValidStart(Timestamp.newBuilder().setSeconds(now))
                        .setAccountID(owner))
                .setCryptoApproveAllowance(approveOp)
                .build();
    }

    private TransactionBody makeApproveTxn(CryptoApproveAllowanceTransactionBody approveOp) {
        return TransactionBody.newBuilder()
                .setTransactionID(TransactionID.newBuilder()
                        .setTransactionValidStart(Timestamp.newBuilder().setSeconds(now))
                        .setAccountID(owner))
                .setCryptoApproveAllowance(approveOp)
                .build();
    }

    private void setDeleteAllowanceTxn() {
        txn = TransactionBody.newBuilder()
                .setTransactionID(TransactionID.newBuilder()
                        .setTransactionValidStart(Timestamp.newBuilder().setSeconds(now))
                        .setAccountID(owner))
                .setCryptoDeleteAllowance(deleteAllowanceOp)
                .build();
    }

    private void givenUpdateOpWithMaxAutoAssociations() {
        updateOp = CryptoUpdateTransactionBody.newBuilder()
                .setExpirationTime(Timestamp.newBuilder().setSeconds(expiry))
                .setProxyAccountID(proxy)
                .setMemo(StringValue.newBuilder().setValue(memo))
                .setKey(key)
                .setMaxAutomaticTokenAssociations(Int32Value.of(maxAutoAssociations))
                .build();
        setUpdateTxn();
    }

    private void setUpdateTxn() {
        txn = TransactionBody.newBuilder()
                .setTransactionID(TransactionID.newBuilder()
                        .setTransactionValidStart(Timestamp.newBuilder().setSeconds(now)))
                .setCryptoUpdateAccount(updateOp)
                .build();
    }

    private void givenCreationOpWithOutMaxAutoAssociaitons() {
        creationOp = CryptoCreateTransactionBody.newBuilder()
                .setProxyAccountID(proxy)
                .setAutoRenewPeriod(Duration.newBuilder().setSeconds(secs).build())
                .setMemo(memo)
                .setKey(key)
                .build();
        setCreateTxn();
    }

    private void givenCreationOpWithMaxAutoAssociaitons() {
        creationOp = CryptoCreateTransactionBody.newBuilder()
                .setProxyAccountID(proxy)
                .setAutoRenewPeriod(Duration.newBuilder().setSeconds(secs).build())
                .setMemo(memo)
                .setKey(key)
                .setMaxAutomaticTokenAssociations(maxAutoAssociations)
                .build();
        setCreateTxn();
    }

    private void setCreateTxn() {
        txn = TransactionBody.newBuilder()
                .setTransactionID(TransactionID.newBuilder()
                        .setTransactionValidStart(Timestamp.newBuilder().setSeconds(now)))
                .setCryptoCreateAccount(creationOp)
                .build();
    }

    private void givenInfoOp() {
        query = Query.newBuilder()
                .setCryptoGetInfo(CryptoGetInfoQuery.newBuilder()
                        .setHeader(QueryHeader.newBuilder().setResponseType(ANSWER_STATE_PROOF)))
                .build();
    }
}
