// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static com.hedera.hapi.node.base.ResponseType.ANSWER_STATE_PROOF;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.token.CryptoAllowance;
import com.hedera.hapi.node.token.CryptoApproveAllowanceTransactionBody;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.CryptoDeleteAllowanceTransactionBody;
import com.hedera.hapi.node.token.CryptoGetInfoQuery;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.hapi.node.token.NftRemoveAllowance;
import com.hedera.hapi.node.token.TokenAllowance;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
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
import com.hedera.pbj.runtime.io.buffer.Bytes;
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
    private final AccountID owner = IdUtils.asAccount("0.0.10000");
    private final int maxAutoAssociations = 123;
    private final int numSigs = 3;
    private final int sigSize = 100;
    private final int numPayerKeys = 1;
    private final CryptoAllowance cryptoAllowances =
            CryptoAllowance.newBuilder().spender(proxy).amount(10L).build();
    private final TokenAllowance tokenAllowances = TokenAllowance.newBuilder()
            .spender(proxy)
            .amount(10L)
            .tokenId(IdUtils.asToken("0.0.1000"))
            .build();
    private final NftAllowance nftAllowances = NftAllowance.newBuilder()
            .spender(proxy)
            .tokenId(IdUtils.asToken("0.0.1000"))
            .serialNumbers(List.of(1L))
            .build();

    private final NftRemoveAllowance nftDeleteAllowances = NftRemoveAllowance.newBuilder()
            .owner(proxy)
            .tokenId(IdUtils.asToken("0.0.1000"))
            .serialNumbers(List.of(1L))
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
        final Bytes canonicalSig = Bytes.wrap("0123456789012345678901234567890123456789012345678901234567890123");
        final SignatureMap onePairSigMap = SignatureMap.newBuilder()
                .sigPair(SignaturePair.newBuilder()
                        .pubKeyPrefix(Bytes.wrap("a"))
                        .ed25519(canonicalSig)
                        .build())
                .build();
        final SigUsage singleSigUsage = new SigUsage(
                1, (int) SignatureMap.PROTOBUF.toBytes(onePairSigMap).length(), 1);
        final var opMeta = new CryptoCreateMeta(txn.cryptoCreateAccount());
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
        final Bytes canonicalSig = Bytes.wrap("0123456789012345678901234567890123456789012345678901234567890123");
        final SignatureMap onePairSigMap = SignatureMap.newBuilder()
                .sigPair(SignaturePair.newBuilder()
                        .pubKeyPrefix(Bytes.wrap("a"))
                        .ed25519(canonicalSig)
                        .build())
                .build();
        final SigUsage singleSigUsage = new SigUsage(
                1, (int) SignatureMap.PROTOBUF.toBytes(onePairSigMap).length(), 1);
        final var opMeta = new CryptoCreateMeta(txn.cryptoCreateAccount());
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
                txn.cryptoUpdateAccount(),
                txn.transactionID().transactionValidStart().seconds());

        expected.resetForTransaction(baseMeta, sigUsage);

        final Key oldKey = FileOpsUsage.asKey(KeyUtils.A_KEY_LIST.keyList());
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
                txn.cryptoUpdateAccount(),
                txn.transactionID().transactionValidStart().seconds());

        expected.resetForTransaction(baseMeta, sigUsage);

        final Key oldKey = FileOpsUsage.asKey(KeyUtils.A_KEY_LIST.keyList());
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
                txn.cryptoUpdateAccount(),
                txn.transactionID().transactionValidStart().seconds());

        expected.resetForTransaction(baseMeta, sigUsage);

        final Key oldKey = FileOpsUsage.asKey(KeyUtils.A_KEY_LIST.keyList());
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

    @Test
    void estimatesApprovalAsExpected() {
        givenApprovalOp();
        final var expected = new UsageAccumulator();
        final var baseMeta = new BaseTransactionMeta(0, 0);
        final var opMeta = new CryptoApproveAllowanceMeta(
                txn.cryptoApproveAllowance(),
                txn.transactionID().transactionValidStart().seconds());
        final SigUsage sigUsage = new SigUsage(1, sigSize, 1);
        expected.resetForTransaction(baseMeta, sigUsage);

        final Key oldKey = FileOpsUsage.asKey(KeyUtils.A_KEY_LIST.keyList());
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

        final long msgBytesUsed = (approveOp.cryptoAllowances().size() * CRYPTO_ALLOWANCE_SIZE)
                + (approveOp.tokenAllowances().size() * TOKEN_ALLOWANCE_SIZE)
                + (approveOp.nftAllowances().size() * NFT_ALLOWANCE_SIZE)
                + countSerials(approveOp.nftAllowances()) * LONG_SIZE;

        expected.addBpt(msgBytesUsed);
        final long lifetime = ESTIMATOR_UTILS.relativeLifetime(txn, oldExpiry);
        final var expectedBytes = (approveOp.cryptoAllowances().size() * CRYPTO_ALLOWANCE_SIZE)
                + (approveOp.tokenAllowances().size() * TOKEN_ALLOWANCE_SIZE)
                + (approveOp.nftAllowances().size() * NFT_ALLOWANCE_SIZE);

        expected.addRbs(expectedBytes * lifetime);

        final var actual = new UsageAccumulator();

        subject.cryptoApproveAllowanceUsage(sigUsage, baseMeta, opMeta, ctx, actual);

        assertEquals(expected, actual);
    }

    @Test
    void estimatesDeleteAsExpected() {
        givenDeleteOp();

        final var expected = new UsageAccumulator();
        final var baseMeta = new BaseTransactionMeta(0, 0);
        final var opMeta = new CryptoDeleteAllowanceMeta(
                txn.cryptoDeleteAllowance(),
                txn.transactionID().transactionValidStart().seconds());
        final SigUsage sigUsage = new SigUsage(1, sigSize, 1);
        expected.resetForTransaction(baseMeta, sigUsage);

        final long msgBytesUsed = (deleteAllowanceOp.nftAllowances().size() * NFT_DELETE_ALLOWANCE_SIZE)
                + countNftDeleteSerials(deleteAllowanceOp.nftAllowances()) * LONG_SIZE;

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
                .expirationTime(Timestamp.newBuilder().seconds(expiry))
                .proxyAccountID(proxy)
                .memo(memo)
                .key(key)
                .build();
        setUpdateTxn();
    }

    private void givenDeleteOp() {
        deleteAllowanceOp = CryptoDeleteAllowanceTransactionBody.newBuilder()
                .nftAllowances(List.of(nftDeleteAllowances))
                .build();
        setDeleteAllowanceTxn();
    }

    private void givenApprovalOp() {
        approveOp = CryptoApproveAllowanceTransactionBody.newBuilder()
                .cryptoAllowances(List.of(cryptoAllowances))
                .tokenAllowances(List.of(tokenAllowances))
                .nftAllowances(List.of(nftAllowances))
                .build();
        setApproveTxn();
    }

    private void setApproveTxn() {
        txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .transactionValidStart(Timestamp.newBuilder().seconds(now))
                        .accountID(owner))
                .cryptoApproveAllowance(approveOp)
                .build();
    }

    private void setDeleteAllowanceTxn() {
        txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .transactionValidStart(Timestamp.newBuilder().seconds(now))
                        .accountID(owner))
                .cryptoDeleteAllowance(deleteAllowanceOp)
                .build();
    }

    private void givenUpdateOpWithMaxAutoAssociations() {
        updateOp = CryptoUpdateTransactionBody.newBuilder()
                .expirationTime(Timestamp.newBuilder().seconds(expiry))
                .proxyAccountID(proxy)
                .memo(memo)
                .key(key)
                .maxAutomaticTokenAssociations(maxAutoAssociations)
                .build();
        setUpdateTxn();
    }

    private void setUpdateTxn() {
        txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .transactionValidStart(Timestamp.newBuilder().seconds(now)))
                .cryptoUpdateAccount(updateOp)
                .build();
    }

    private void givenCreationOpWithOutMaxAutoAssociaitons() {
        creationOp = CryptoCreateTransactionBody.newBuilder()
                .proxyAccountID(proxy)
                .autoRenewPeriod(Duration.newBuilder().seconds(secs).build())
                .memo(memo)
                .key(key)
                .build();
        setCreateTxn();
    }

    private void givenCreationOpWithMaxAutoAssociaitons() {
        creationOp = CryptoCreateTransactionBody.newBuilder()
                .proxyAccountID(proxy)
                .autoRenewPeriod(Duration.newBuilder().seconds(secs).build())
                .memo(memo)
                .key(key)
                .maxAutomaticTokenAssociations(maxAutoAssociations)
                .build();
        setCreateTxn();
    }

    private void setCreateTxn() {
        txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .transactionValidStart(Timestamp.newBuilder().seconds(now)))
                .cryptoCreateAccount(creationOp)
                .build();
    }

    private void givenInfoOp() {
        query = Query.newBuilder()
                .cryptoGetInfo(CryptoGetInfoQuery.newBuilder()
                        .header(QueryHeader.newBuilder().responseType(ANSWER_STATE_PROOF)))
                .build();
    }
}
