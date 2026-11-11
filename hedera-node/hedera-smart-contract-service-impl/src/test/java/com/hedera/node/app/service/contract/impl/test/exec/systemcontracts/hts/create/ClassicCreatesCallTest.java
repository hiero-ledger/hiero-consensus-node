// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.create;

import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy.Decision.DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION;
import static com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy.Decision.INVALID;
import static com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy.Decision.VALID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_FUNGIBLE_TOKEN_WITH_METADATA;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c.CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CONFIG_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ALIASED_SOMEBODY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.AN_ED25519_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.readableRevertReason;
import static com.hedera.node.app.service.contract.impl.utils.ConstantUtils.ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.AccountAuthorizingVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.ClassicCreatesCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x167.CreateTranslator;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/**
 * Unit tests for create token calls.
 */
public class ClassicCreatesCallTest extends CallTestBase {
    private static final org.hyperledger.besu.datatypes.Address FRAME_SENDER_ADDRESS = EIP_1014_ADDRESS;
    // Matches the default contracts.keys.legacyActivations
    private static final long LEGACY_ACTIVATION_NUM = 1062784L;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    @Mock
    private BlockValues blockValues;

    @Mock
    private ProxyWorldUpdater updater;

    private static final TransactionBody PRETEND_CREATE_TOKEN = TransactionBody.newBuilder()
            .tokenCreation(TokenCreateTransactionBody.newBuilder()
                    .symbol("FT")
                    .treasury(A_NEW_ACCOUNT_ID)
                    .autoRenewAccount(SENDER_ID)
                    .build())
            .build();

    private ClassicCreatesCall subject;

    private final Deque<MessageFrame> stack = new ArrayDeque<>();

    private final Address tokenId =
            Address.wrap(Address.toChecksumAddress(BigInteger.valueOf(FUNGIBLE_TOKEN_ID.tokenNum())));

    @Test
    void createFungibleTokenHappyPathV1() {
        commonGivens();
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CreateTranslator.CREATE_FUNGIBLE_TOKEN_V1
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), tokenId))
                        .array()),
                result.output());
    }

    @Test
    void createFungibleTokenHappyPathV2() {
        commonGivens();
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CreateTranslator.CREATE_FUNGIBLE_TOKEN_V2
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), tokenId))
                        .array()),
                result.output());
    }

    @Test
    void createFungibleTokenHappyPathV3() {
        commonGivens();
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CreateTranslator.CREATE_FUNGIBLE_TOKEN_V3
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), tokenId))
                        .array()),
                result.output());
    }

    @Test
    void createFungibleTokenWitheMetaHappyPath() {
        commonGivens();
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CREATE_FUNGIBLE_TOKEN_WITH_METADATA
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), tokenId))
                        .array()),
                result.output());
    }

    @Test
    void createFungibleTokenWithCustomFeesHappyPathV1() {
        commonGivens();
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CreateTranslator.CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V1
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), tokenId))
                        .array()),
                result.output());
    }

    @Test
    void createFungibleTokenWithCustomFeesHappyPathV2() {
        commonGivens();
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CreateTranslator.CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V2
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), tokenId))
                        .array()),
                result.output());
    }

    @Test
    void createFungibleTokenWithCustomFeesHappyPathV3() {
        commonGivens();
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CreateTranslator.CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V3
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), tokenId))
                        .array()),
                result.output());
    }

    @Test
    void createFungibleTokenWithMetaAndCustomFeesHappyPath() {
        commonGivens();
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), tokenId))
                        .array()),
                result.output());
    }

    @Test
    void createNonFungibleTokenHappyPathV1() {
        commonGivens();
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_V1
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), tokenId))
                        .array()),
                result.output());
    }

    @Test
    void createNonFungibleTokenHappyPathV2() {
        commonGivens();
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_V2
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), tokenId))
                        .array()),
                result.output());
    }

    @Test
    void createNonFungibleTokenHappyPathV3() {
        commonGivens();
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_V3
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), tokenId))
                        .array()),
                result.output());
    }

    @Test
    void createNonFungibleTokenWithMetaHappyPath() {
        commonGivens();
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), tokenId))
                        .array()),
                result.output());
    }

    @Test
    void createNonFungibleTokenWithCustomFeesHappyPathV1() {
        commonGivens();
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V1
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), tokenId))
                        .array()),
                result.output());
    }

    @Test
    void createNonFungibleTokenWithCustomFeesHappyPathV2() {
        commonGivens();
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V2
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), tokenId))
                        .array()),
                result.output());
    }

    @Test
    void createNonFungibleTokenWithCustomFeesHappyPathV3() {
        commonGivens();
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V3
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), tokenId))
                        .array()),
                result.output());
    }

    @Test
    void createNonFungibleTokenWithMetaAndCustomFeesHappyPath() {
        commonGivens();
        given(recordBuilder.status()).willReturn(SUCCESS);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES
                        .getOutputs()
                        .encode(Tuple.of((long) SUCCESS.protoOrdinal(), tokenId))
                        .array()),
                result.output());
    }

    @Test
    void requiresNonGasCostToBeProvidedAsValue() {
        commonGivens(200_000L, 99_999L, true);
        given(recordBuilder.status()).willReturn(SUCCESS);
        given(systemContractOperations.externalizePreemptedDispatch(any(), eq(INSUFFICIENT_TX_FEE), eq(TOKEN_CREATE)))
                .willReturn(recordBuilder);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.COMPLETED_SUCCESS, result.state());
        assertEquals(
                Bytes.wrap(CreateTranslator.CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V3
                        .getOutputs()
                        .encode(Tuple.of((long) INSUFFICIENT_TX_FEE.protoOrdinal(), ZERO_ADDRESS))
                        .array()),
                result.output());
    }

    @Test
    void createFungibleTokenUnhappyPathRevertsWithReason() {
        commonGivens();
        given(recordBuilder.status()).willReturn(TOKEN_HAS_NO_SUPPLY_KEY);

        final var result = subject.execute(frame).fullResult().result();

        assertEquals(MessageFrame.State.REVERT, result.state());
        assertEquals(readableRevertReason(recordBuilder.status()), result.output());
    }

    @Test
    void cryptoAdminKeyAndLegacyActivationKeepDispatchAccountAuthority() {
        final var body = PRETEND_CREATE_TOKEN
                .copyBuilder()
                .tokenCreation(PRETEND_CREATE_TOKEN
                        .tokenCreationOrThrow()
                        .copyBuilder()
                        .adminKey(AN_ED25519_KEY)
                        .build())
                .build();
        final var strategy = new AccountAuthorizingVerificationStrategy(SENDER_ID, key -> INVALID);
        final var legacyCaller = mock(MessageFrame.class);
        given(legacyCaller.getRecipientAddress()).willReturn(asLongZeroAddress(LEGACY_ACTIVATION_NUM));
        given(legacyCaller.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(DEFAULT_CONFIG);
        stack.push(legacyCaller);
        stack.push(frame);
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(frame.getWorldUpdater()).willReturn(updater);
        given(updater.entityIdFactory()).willReturn(entityIdFactory);
        given(frame.getValue()).willReturn(Wei.ZERO);
        given(frame.getBlockValues()).willReturn(blockValues);
        given(blockValues.getTimestamp()).willReturn(Timestamp.DEFAULT.seconds());
        given(nativeOperations.getAccount(A_NEW_ACCOUNT_ID)).willReturn(ALIASED_SOMEBODY);
        given(systemContractOperations.dispatch(
                        any(TransactionBody.class),
                        any(VerificationStrategy.class),
                        eq(A_NEW_ACCOUNT_ID),
                        eq(ContractCallStreamBuilder.class)))
                .willReturn(recordBuilder);
        given(recordBuilder.status()).willReturn(SUCCESS);
        given(recordBuilder.tokenID()).willReturn(FUNGIBLE_TOKEN_ID);
        subject = new ClassicCreatesCall(gasCalculator, mockEnhancement(), body, strategy, A_NEW_ACCOUNT_ID);

        subject.execute(frame);

        final var captor = ArgumentCaptor.forClass(VerificationStrategy.class);
        verify(systemContractOperations)
                .dispatch(
                        any(TransactionBody.class),
                        captor.capture(),
                        eq(A_NEW_ACCOUNT_ID),
                        eq(ContractCallStreamBuilder.class));
        final var dispatchStrategy = captor.getValue();
        assertTrue(dispatchStrategy.authorizesAccount(SENDER_ID));
        assertEquals(DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION, dispatchStrategy.decideForPrimitive(AN_ED25519_KEY));
        assertEquals(
                VALID,
                dispatchStrategy.decideForPrimitive(Key.newBuilder()
                        .contractID(entityIdFactory.newContractId(LEGACY_ACTIVATION_NUM))
                        .build()));
    }

    @Test
    void isSchedulableDispatchInFailsWithNullBody() {
        // given
        subject =
                new ClassicCreatesCall(gasCalculator, mockEnhancement(), null, verificationStrategy, A_NEW_ACCOUNT_ID);

        // when/then
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> subject.asSchedulableDispatchIn())
                .withMessage("Needs scheduleNative() support");
    }

    @Test
    void isSchedulableDispatchInHappyPath() {
        // given
        final var txnBody = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.DEFAULT)
                .build();
        final var expectedBody = SchedulableTransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.DEFAULT)
                .build();
        subject = new ClassicCreatesCall(
                gasCalculator, mockEnhancement(), txnBody, verificationStrategy, A_NEW_ACCOUNT_ID);

        // when/then
        final var response = subject.asSchedulableDispatchIn();
        assertEquals(expectedBody, response);
    }

    private void commonGivens() {
        commonGivens(0L, 0L, false);
    }

    private void commonGivens(long baseCost, long value, boolean shouldBePreempted) {
        given(frame.getValue()).willReturn(Wei.of(value));
        given(gasCalculator.feeCalculatorPriceInTinyBars(any(), any())).willReturn(baseCost);
        stack.push(frame);

        if (!shouldBePreempted) {
            given(frame.getMessageFrameStack()).willReturn(stack);
            given(frame.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(DEFAULT_CONFIG);
            given(nativeOperations.getAccount(A_NEW_ACCOUNT_ID)).willReturn(ALIASED_SOMEBODY);
            given(systemContractOperations.dispatch(
                            any(TransactionBody.class),
                            eq(verificationStrategy),
                            eq(A_NEW_ACCOUNT_ID),
                            eq(ContractCallStreamBuilder.class)))
                    .willReturn(recordBuilder);
        }
        given(frame.getBlockValues()).willReturn(blockValues);
        given(blockValues.getTimestamp()).willReturn(Timestamp.DEFAULT.seconds());
        subject = new ClassicCreatesCall(
                gasCalculator, mockEnhancement(), PRETEND_CREATE_TOKEN, verificationStrategy, A_NEW_ACCOUNT_ID);

        lenient().when(recordBuilder.tokenID()).thenReturn(FUNGIBLE_TOKEN_ID);
    }
}
