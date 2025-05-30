// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static com.hedera.node.app.hapi.utils.CommonUtils.asEvmAddress;
import static com.hedera.node.app.hapi.utils.CommonUtils.functionOf;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.node.app.hapi.utils.CommonUtils.productWouldOverflow;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusCreateTopic;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusDeleteTopic;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusSubmitMessage;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusUpdateTopic;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCall;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractDelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoAddLiveHash;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoApproveAllowance;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoDelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoDeleteLiveHash;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.EthereumTransaction;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileAppend;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileDelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.Freeze;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ScheduleCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ScheduleDelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ScheduleSign;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.SystemDelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.SystemUndelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAccountWipe;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAssociateToAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenBurn;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenDelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenDissociateFromAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenFeeScheduleUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenFreezeAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenGrantKycToAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenMint;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenPause;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenRevokeKycFromAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenUnfreezeAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenUnpause;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.UncheckedSubmit;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.UtilPrng;
import static com.hederahashgraph.api.proto.java.ResponseType.ANSWER_ONLY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ConsensusCreateTopicTransactionBody;
import com.hederahashgraph.api.proto.java.ConsensusDeleteTopicTransactionBody;
import com.hederahashgraph.api.proto.java.ConsensusSubmitMessageTransactionBody;
import com.hederahashgraph.api.proto.java.ConsensusUpdateTopicTransactionBody;
import com.hederahashgraph.api.proto.java.ContractCallTransactionBody;
import com.hederahashgraph.api.proto.java.ContractCreateTransactionBody;
import com.hederahashgraph.api.proto.java.ContractDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.ContractUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoAddLiveHashTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoApproveAllowanceTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoDeleteLiveHashTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.EthereumTransactionBody;
import com.hederahashgraph.api.proto.java.FileAppendTransactionBody;
import com.hederahashgraph.api.proto.java.FileCreateTransactionBody;
import com.hederahashgraph.api.proto.java.FileDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.FileUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.FreezeTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.QueryHeader;
import com.hederahashgraph.api.proto.java.ScheduleCreateTransactionBody;
import com.hederahashgraph.api.proto.java.ScheduleDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.ScheduleSignTransactionBody;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignaturePair;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.SystemDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.SystemUndeleteTransactionBody;
import com.hederahashgraph.api.proto.java.TokenAssociateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenBurnTransactionBody;
import com.hederahashgraph.api.proto.java.TokenCreateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.TokenDissociateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenFeeScheduleUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenFreezeAccountTransactionBody;
import com.hederahashgraph.api.proto.java.TokenGrantKycTransactionBody;
import com.hederahashgraph.api.proto.java.TokenMintTransactionBody;
import com.hederahashgraph.api.proto.java.TokenPauseTransactionBody;
import com.hederahashgraph.api.proto.java.TokenRevokeKycTransactionBody;
import com.hederahashgraph.api.proto.java.TokenUnfreezeAccountTransactionBody;
import com.hederahashgraph.api.proto.java.TokenUnpauseTransactionBody;
import com.hederahashgraph.api.proto.java.TokenUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenWipeAccountTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody.Builder;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.UncheckedSubmitBody;
import com.hederahashgraph.api.proto.java.UtilPrngTransactionBody;
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
    void returnsAvailableTransactionBodyBytes() throws InvalidProtocolBufferException {
        final var current = Transaction.newBuilder()
                .setSignedTransactionBytes(SignedTransaction.newBuilder()
                        .setBodyBytes(NONSENSE)
                        .build()
                        .toByteString())
                .build();
        final var deprecated = Transaction.newBuilder().setBodyBytes(NONSENSE).build();

        assertEquals(NONSENSE, CommonUtils.extractTransactionBodyByteString(current));
        assertEquals(NONSENSE, CommonUtils.extractTransactionBodyByteString(deprecated));
        assertArrayEquals(NONSENSE.toByteArray(), CommonUtils.extractTransactionBodyBytes(current));
    }

    @Test
    void canExtractTransactionBody() throws InvalidProtocolBufferException {
        final var body = TransactionBody.newBuilder()
                .setTransactionID(TransactionID.newBuilder()
                        .setAccountID(AccountID.newBuilder().setAccountNum(2L).build()))
                .build();
        final var current = Transaction.newBuilder()
                .setSignedTransactionBytes(SignedTransaction.newBuilder()
                        .setBodyBytes(body.toByteString())
                        .build()
                        .toByteString())
                .build();
        assertEquals(body, CommonUtils.extractTransactionBody(current));
    }

    @Test
    void returnsAvailableSigMap() throws InvalidProtocolBufferException {
        final var sigMap = SignatureMap.newBuilder()
                .addSigPair(SignaturePair.newBuilder()
                        .setPubKeyPrefix(NONSENSE)
                        .setEd25519(NONSENSE)
                        .build())
                .build();
        final var current = Transaction.newBuilder()
                .setSignedTransactionBytes(
                        SignedTransaction.newBuilder().setSigMap(sigMap).build().toByteString())
                .build();
        final var deprecated = Transaction.newBuilder().setSigMap(sigMap).build();

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
        assertThrows(
                com.hedera.hapi.util.UnknownHederaFunctionality.class,
                () -> functionOf(TransactionBody.getDefaultInstance()));
    }

    @Test
    void getsExpectedTxnFunctionality() {
        final Map<HederaFunctionality, BodySetter<? extends GeneratedMessage, Builder>> setters = new HashMap<>() {
            {
                put(SystemDelete, new BodySetter<>(SystemDeleteTransactionBody.class));
                put(SystemUndelete, new BodySetter<>(SystemUndeleteTransactionBody.class));
                put(ContractCall, new BodySetter<>(ContractCallTransactionBody.class));
                put(ContractCreate, new BodySetter<>(ContractCreateTransactionBody.class));
                put(EthereumTransaction, new BodySetter<>(EthereumTransactionBody.class));
                put(ContractUpdate, new BodySetter<>(ContractUpdateTransactionBody.class));
                put(CryptoAddLiveHash, new BodySetter<>(CryptoAddLiveHashTransactionBody.class));
                put(CryptoCreate, new BodySetter<>(CryptoCreateTransactionBody.class));
                put(CryptoDelete, new BodySetter<>(CryptoDeleteTransactionBody.class));
                put(CryptoDeleteLiveHash, new BodySetter<>(CryptoDeleteLiveHashTransactionBody.class));
                put(CryptoTransfer, new BodySetter<>(CryptoTransferTransactionBody.class));
                put(CryptoUpdate, new BodySetter<>(CryptoUpdateTransactionBody.class));
                put(FileAppend, new BodySetter<>(FileAppendTransactionBody.class));
                put(FileCreate, new BodySetter<>(FileCreateTransactionBody.class));
                put(FileDelete, new BodySetter<>(FileDeleteTransactionBody.class));
                put(FileUpdate, new BodySetter<>(FileUpdateTransactionBody.class));
                put(ContractDelete, new BodySetter<>(ContractDeleteTransactionBody.class));
                put(TokenCreate, new BodySetter<>(TokenCreateTransactionBody.class));
                put(TokenFreezeAccount, new BodySetter<>(TokenFreezeAccountTransactionBody.class));
                put(TokenUnfreezeAccount, new BodySetter<>(TokenUnfreezeAccountTransactionBody.class));
                put(TokenGrantKycToAccount, new BodySetter<>(TokenGrantKycTransactionBody.class));
                put(TokenRevokeKycFromAccount, new BodySetter<>(TokenRevokeKycTransactionBody.class));
                put(TokenDelete, new BodySetter<>(TokenDeleteTransactionBody.class));
                put(TokenUpdate, new BodySetter<>(TokenUpdateTransactionBody.class));
                put(TokenMint, new BodySetter<>(TokenMintTransactionBody.class));
                put(TokenBurn, new BodySetter<>(TokenBurnTransactionBody.class));
                put(TokenAccountWipe, new BodySetter<>(TokenWipeAccountTransactionBody.class));
                put(TokenAssociateToAccount, new BodySetter<>(TokenAssociateTransactionBody.class));
                put(TokenDissociateFromAccount, new BodySetter<>(TokenDissociateTransactionBody.class));
                put(TokenUnpause, new BodySetter<>(TokenUnpauseTransactionBody.class));
                put(TokenPause, new BodySetter<>(TokenPauseTransactionBody.class));
                put(ScheduleCreate, new BodySetter<>(ScheduleCreateTransactionBody.class));
                put(ScheduleSign, new BodySetter<>(ScheduleSignTransactionBody.class));
                put(ScheduleDelete, new BodySetter<>(ScheduleDeleteTransactionBody.class));
                put(Freeze, new BodySetter<>(FreezeTransactionBody.class));
                put(ConsensusCreateTopic, new BodySetter<>(ConsensusCreateTopicTransactionBody.class));
                put(ConsensusUpdateTopic, new BodySetter<>(ConsensusUpdateTopicTransactionBody.class));
                put(ConsensusDeleteTopic, new BodySetter<>(ConsensusDeleteTopicTransactionBody.class));
                put(ConsensusSubmitMessage, new BodySetter<>(ConsensusSubmitMessageTransactionBody.class));
                put(UncheckedSubmit, new BodySetter<>(UncheckedSubmitBody.class));
                put(TokenFeeScheduleUpdate, new BodySetter<>(TokenFeeScheduleUpdateTransactionBody.class));
                put(UtilPrng, new BodySetter<>(UtilPrngTransactionBody.class));
                put(CryptoApproveAllowance, new BodySetter<>(CryptoApproveAllowanceTransactionBody.class));
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

    private static final ByteString NONSENSE = ByteString.copyFromUtf8("NONSENSE");

    private static class BodySetter<T, B> {
        private final Class<T> type;

        public BodySetter(final Class<T> type) {
            this.type = type;
        }

        void setDefaultInstanceFor(final B builder) {
            try {
                final var setter = getSetter(builder, type);
                final var defaultGetter = type.getDeclaredMethod("getDefaultInstance");
                final T defaultInstance = (T) defaultGetter.invoke(null);
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
                setHeaderMethod.invoke(opBuilder, QueryHeader.newBuilder().setResponseType(ANSWER_ONLY));
                final var setter = getSetter(builder, opBuilderClass);
                setter.invoke(builder, opBuilder);
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private Method getSetter(final B builder, final Class type) {
            return Stream.of(builder.getClass().getDeclaredMethods())
                    .filter(m -> m.getName().startsWith("set") && m.getParameterTypes()[0].equals(type))
                    .findFirst()
                    .get();
        }
    }

    @Test
    void getExpectEvmAddress() {
        final var address = new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 123};
        final var evmAddress = asEvmAddress(123L);
        assertArrayEquals(address, evmAddress);
    }
}
