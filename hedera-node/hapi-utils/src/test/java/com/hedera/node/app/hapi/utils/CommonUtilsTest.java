// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_DELETE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_UPDATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_ADD_LIVE_HASH;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_DELETE_LIVE_HASH;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_APPEND;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.FREEZE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_SIGN;
import static com.hedera.hapi.node.base.HederaFunctionality.SYSTEM_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.SYSTEM_UNDELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ACCOUNT_WIPE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_BURN;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_DISSOCIATE_FROM_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_FEE_SCHEDULE_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_FREEZE_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_GRANT_KYC_TO_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_PAUSE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_REVOKE_KYC_FROM_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UNFREEZE_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UNPAUSE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.UNCHECKED_SUBMIT;
import static com.hedera.hapi.node.base.HederaFunctionality.UTIL_PRNG;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static com.hedera.node.app.hapi.utils.CommonUtils.asEvmAddress;
import static com.hedera.node.app.hapi.utils.CommonUtils.functionOf;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.node.app.hapi.utils.CommonUtils.productWouldOverflow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.consensus.ConsensusCreateTopicTransactionBody;
import com.hedera.hapi.node.consensus.ConsensusDeleteTopicTransactionBody;
import com.hedera.hapi.node.consensus.ConsensusSubmitMessageTransactionBody;
import com.hedera.hapi.node.consensus.ConsensusUpdateTopicTransactionBody;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.ContractDeleteTransactionBody;
import com.hedera.hapi.node.contract.ContractUpdateTransactionBody;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.file.FileAppendTransactionBody;
import com.hedera.hapi.node.file.FileCreateTransactionBody;
import com.hedera.hapi.node.file.FileDeleteTransactionBody;
import com.hedera.hapi.node.file.FileUpdateTransactionBody;
import com.hedera.hapi.node.file.SystemDeleteTransactionBody;
import com.hedera.hapi.node.file.SystemUndeleteTransactionBody;
import com.hedera.hapi.node.freeze.FreezeTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleDeleteTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleSignTransactionBody;
import com.hedera.hapi.node.token.CryptoAddLiveHashTransactionBody;
import com.hedera.hapi.node.token.CryptoApproveAllowanceTransactionBody;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.CryptoDeleteLiveHashTransactionBody;
import com.hedera.hapi.node.token.CryptoDeleteTransactionBody;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.token.TokenAssociateTransactionBody;
import com.hedera.hapi.node.token.TokenBurnTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.token.TokenDeleteTransactionBody;
import com.hedera.hapi.node.token.TokenDissociateTransactionBody;
import com.hedera.hapi.node.token.TokenFeeScheduleUpdateTransactionBody;
import com.hedera.hapi.node.token.TokenFreezeAccountTransactionBody;
import com.hedera.hapi.node.token.TokenGrantKycTransactionBody;
import com.hedera.hapi.node.token.TokenMintTransactionBody;
import com.hedera.hapi.node.token.TokenPauseTransactionBody;
import com.hedera.hapi.node.token.TokenRevokeKycTransactionBody;
import com.hedera.hapi.node.token.TokenUnfreezeAccountTransactionBody;
import com.hedera.hapi.node.token.TokenUnpauseTransactionBody;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.token.TokenWipeAccountTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.UncheckedSubmitBody;
import com.hedera.hapi.node.util.UtilPrngTransactionBody;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommonUtilsTest {
    @Test
    void base64EncodesAsExpected() {
        final var someBytes = "abcdefg".getBytes();
        assertEquals(Base64.getEncoder().encodeToString(someBytes), CommonUtils.base64encode(someBytes));
    }

    @Test
    void returnsAvailableTransactionBodyBytes() throws ParseException {
        final var current = Transaction.newBuilder()
                .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(
                        SignedTransaction.newBuilder().bodyBytes(NONSENSE).build()))
                .build();
        final var deprecated = Transaction.newBuilder().bodyBytes(NONSENSE).build();

        assertEquals(NONSENSE, CommonUtils.extractTransactionBodyByteString(current));
        assertEquals(NONSENSE, CommonUtils.extractTransactionBodyByteString(deprecated));
        assertArrayEquals(NONSENSE.toByteArray(), CommonUtils.extractTransactionBodyBytes(current));
    }

    @Test
    void canExtractTransactionBody() throws ParseException {
        final var body = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(AccountID.newBuilder().accountNum(2L).build()))
                .build();
        final var current = Transaction.newBuilder()
                .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(SignedTransaction.newBuilder()
                        .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                        .build()))
                .build();
        assertEquals(body, CommonUtils.extractTransactionBody(current));
    }

    @Test
    void returnsAvailableSigMap() throws ParseException {
        final var sigMap = SignatureMap.newBuilder()
                .sigPair(SignaturePair.newBuilder()
                        .pubKeyPrefix(NONSENSE)
                        .ed25519(NONSENSE)
                        .build())
                .build();
        final var current = Transaction.newBuilder()
                .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(
                        SignedTransaction.newBuilder().sigMap(sigMap).build()))
                .build();
        final var deprecated = Transaction.newBuilder().sigMap(sigMap).build();

        assertEquals(sigMap, CommonUtils.extractSignatureMap(current));
        assertEquals(sigMap, CommonUtils.extractSignatureMap(deprecated));
    }

    @Test
    void failsOnUnavailableDigest() {
        final var raw = NONSENSE.toByteArray();
        assertDoesNotThrow(() -> noThrowSha384HashOf(raw));
        CommonUtils.setSha384HashTag("NOPE");
        assertThrows(IllegalStateException.class, () -> noThrowSha384HashOf(raw));
        CommonUtils.setSha384HashTag("SHA-384");
    }

    @Test
    void detectsOverflowInVariousCases() {
        final var nonZeroMultiplicand = 666L;
        final var fineMultiplier = Long.MAX_VALUE / nonZeroMultiplicand;
        final var overflowMultiplier = Long.MAX_VALUE / nonZeroMultiplicand + 1;
        assertFalse(productWouldOverflow(fineMultiplier, nonZeroMultiplicand));
        assertFalse(productWouldOverflow(fineMultiplier, 0));
        assertTrue(productWouldOverflow(overflowMultiplier, nonZeroMultiplicand));
    }

    @Test
    void throwsOnUnexpectedFunctionality() {
        assertThrows(com.hedera.hapi.util.UnknownHederaFunctionality.class, () -> functionOf(TransactionBody.DEFAULT));
    }

    @Test
    void getsExpectedTxnFunctionality() {
        final Map<HederaFunctionality, BodySetter<?, Object>> setters = new HashMap<>() {
            {
                put(SYSTEM_DELETE, new BodySetter<>(SystemDeleteTransactionBody.class));
                put(SYSTEM_UNDELETE, new BodySetter<>(SystemUndeleteTransactionBody.class));
                put(CONTRACT_CALL, new BodySetter<>(ContractCallTransactionBody.class));
                put(CONTRACT_CREATE, new BodySetter<>(ContractCreateTransactionBody.class));
                put(ETHEREUM_TRANSACTION, new BodySetter<>(EthereumTransactionBody.class));
                put(CONTRACT_UPDATE, new BodySetter<>(ContractUpdateTransactionBody.class));
                put(CRYPTO_ADD_LIVE_HASH, new BodySetter<>(CryptoAddLiveHashTransactionBody.class));
                put(CRYPTO_CREATE, new BodySetter<>(CryptoCreateTransactionBody.class));
                put(CRYPTO_DELETE, new BodySetter<>(CryptoDeleteTransactionBody.class));
                put(CRYPTO_DELETE_LIVE_HASH, new BodySetter<>(CryptoDeleteLiveHashTransactionBody.class));
                put(CRYPTO_TRANSFER, new BodySetter<>(CryptoTransferTransactionBody.class));
                put(CRYPTO_UPDATE, new BodySetter<>(CryptoUpdateTransactionBody.class));
                put(FILE_APPEND, new BodySetter<>(FileAppendTransactionBody.class));
                put(FILE_CREATE, new BodySetter<>(FileCreateTransactionBody.class));
                put(FILE_DELETE, new BodySetter<>(FileDeleteTransactionBody.class));
                put(FILE_UPDATE, new BodySetter<>(FileUpdateTransactionBody.class));
                put(CONTRACT_DELETE, new BodySetter<>(ContractDeleteTransactionBody.class));
                put(TOKEN_CREATE, new BodySetter<>(TokenCreateTransactionBody.class));
                put(TOKEN_FREEZE_ACCOUNT, new BodySetter<>(TokenFreezeAccountTransactionBody.class));
                put(TOKEN_UNFREEZE_ACCOUNT, new BodySetter<>(TokenUnfreezeAccountTransactionBody.class));
                put(TOKEN_GRANT_KYC_TO_ACCOUNT, new BodySetter<>(TokenGrantKycTransactionBody.class));
                put(TOKEN_REVOKE_KYC_FROM_ACCOUNT, new BodySetter<>(TokenRevokeKycTransactionBody.class));
                put(TOKEN_DELETE, new BodySetter<>(TokenDeleteTransactionBody.class));
                put(TOKEN_UPDATE, new BodySetter<>(TokenUpdateTransactionBody.class));
                put(TOKEN_MINT, new BodySetter<>(TokenMintTransactionBody.class));
                put(TOKEN_BURN, new BodySetter<>(TokenBurnTransactionBody.class));
                put(TOKEN_ACCOUNT_WIPE, new BodySetter<>(TokenWipeAccountTransactionBody.class));
                put(TOKEN_ASSOCIATE_TO_ACCOUNT, new BodySetter<>(TokenAssociateTransactionBody.class));
                put(TOKEN_DISSOCIATE_FROM_ACCOUNT, new BodySetter<>(TokenDissociateTransactionBody.class));
                put(TOKEN_UNPAUSE, new BodySetter<>(TokenUnpauseTransactionBody.class));
                put(TOKEN_PAUSE, new BodySetter<>(TokenPauseTransactionBody.class));
                put(SCHEDULE_CREATE, new BodySetter<>(ScheduleCreateTransactionBody.class));
                put(SCHEDULE_SIGN, new BodySetter<>(ScheduleSignTransactionBody.class));
                put(SCHEDULE_DELETE, new BodySetter<>(ScheduleDeleteTransactionBody.class));
                put(FREEZE, new BodySetter<>(FreezeTransactionBody.class));
                put(CONSENSUS_CREATE_TOPIC, new BodySetter<>(ConsensusCreateTopicTransactionBody.class));
                put(CONSENSUS_UPDATE_TOPIC, new BodySetter<>(ConsensusUpdateTopicTransactionBody.class));
                put(CONSENSUS_DELETE_TOPIC, new BodySetter<>(ConsensusDeleteTopicTransactionBody.class));
                put(CONSENSUS_SUBMIT_MESSAGE, new BodySetter<>(ConsensusSubmitMessageTransactionBody.class));
                put(UNCHECKED_SUBMIT, new BodySetter<>(UncheckedSubmitBody.class));
                put(TOKEN_FEE_SCHEDULE_UPDATE, new BodySetter<>(TokenFeeScheduleUpdateTransactionBody.class));
                put(UTIL_PRNG, new BodySetter<>(UtilPrngTransactionBody.class));
                put(CRYPTO_APPROVE_ALLOWANCE, new BodySetter<>(CryptoApproveAllowanceTransactionBody.class));
            }
        };

        setters.forEach((function, setter) -> {
            final var txn = TransactionBody.newBuilder();
            setter.setDefaultInstanceFor(txn);
            try {
                final var input = txn.build();
                assertEquals(function, functionOf(input));
            } catch (final com.hedera.hapi.util.UnknownHederaFunctionality uhf) {
                Assertions.fail("Failed HederaFunctionality check :: " + uhf.getMessage());
            }
        });
    }

    private static final Bytes NONSENSE = Bytes.wrap("NONSENSE");

    private static class BodySetter<T, B> {
        private final Class<T> type;

        public BodySetter(final Class<T> type) {
            this.type = type;
        }

        void setDefaultInstanceFor(final B builder) {
            try {
                final var setter = getSetter(builder, type);
                final var defaultField = type.getDeclaredField("DEFAULT");
                final T defaultInstance = (T) defaultField.get(null);
                setter.invoke(builder, defaultInstance);
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }

        void setActiveHeaderFor(final B builder) {
            try {
                final var newBuilderMethod = type.getDeclaredMethod("newBuilder");
                final var opBuilder = newBuilderMethod.invoke(null);
                final var opBuilderClass = opBuilder.getClass();
                final var setHeaderMethod = opBuilderClass.getDeclaredMethod("setHeader", QueryHeader.Builder.class);
                setHeaderMethod.invoke(opBuilder, QueryHeader.newBuilder().responseType(ANSWER_ONLY));
                final var setter = getSetter(builder, opBuilderClass);
                setter.invoke(builder, opBuilder);
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private Method getSetter(final B builder, final Class type) {
            return Stream.of(builder.getClass().getDeclaredMethods())
                    .filter(m -> !m.getName().startsWith("get")
                            && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].equals(type))
                    .findFirst()
                    .get();
        }
    }

    @Test
    void getExpectEvmAddress() {
        final var address = new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 123};
        final var evmAddress = asEvmAddress(0, 0, 123L);
        assertArrayEquals(address, evmAddress);
    }
}
