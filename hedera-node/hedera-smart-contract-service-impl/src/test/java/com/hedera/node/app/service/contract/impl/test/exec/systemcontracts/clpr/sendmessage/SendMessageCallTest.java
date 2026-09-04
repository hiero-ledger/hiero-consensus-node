// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.clpr.sendmessage;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_AUTHORIZATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_SERVICE_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_ACCOUNTS_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CLPR_DISPATCH;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.STATIC_CALL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.assertArg;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.node.app.service.clpr.ClprServiceApi;
import com.hedera.node.app.service.clpr.ReadableConnectorStore;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.sendmessage.SendMessageCall;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.ClprDispatchMetadata;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SendMessageCallTest extends CallTestBase {

    private static final AccountID SENDER_ID =
            AccountID.newBuilder().accountNum(1001).build();
    private static final Address SENDER_ADDRESS = Address.fromHexString("0x1234567890abcdef1234567890abcdef12345678");
    private static final byte[] CHANNEL_ID = new byte[32];
    private static final byte[] CONNECTOR_ID = {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
        31, 32
    };
    private static final byte[] TARGET_APP = new byte[] {10, 20, 30};
    private static final byte[] MESSAGE_DATA = new byte[] {1, 2, 3, 4, 5};

    private static final Bytes CONNECTOR_ID_BYTES = Bytes.wrap(CONNECTOR_ID);
    private static final ContractID CONNECTOR_CONTRACT_ID =
            ContractID.newBuilder().contractNum(9999L).build();

    // A 32-byte ABI bool(true) return value
    private static final Bytes BOOL_TRUE_RESULT;
    // A 32-byte ABI bool(false) return value
    private static final Bytes BOOL_FALSE_RESULT;

    static {
        final byte[] trueBytes = new byte[32];
        trueBytes[31] = 1;
        BOOL_TRUE_RESULT = Bytes.wrap(trueBytes);
        BOOL_FALSE_RESULT = Bytes.wrap(new byte[32]);
    }

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private ClprServiceApi clprApi;

    @Mock
    private ReadableConnectorStore connectorStore;

    private ClprConnector connectorWithContract() {
        return ClprConnector.newBuilder()
                .connectorId(CONNECTOR_ID_BYTES)
                .channelId(Bytes.wrap(CHANNEL_ID))
                .connectorContract(CONNECTOR_CONTRACT_ID)
                .build();
    }

    private void givenConnectorLookup(final ClprConnector connector) {
        given(nativeOperations.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableConnectorStore.class)).willReturn(connectorStore);
        final var key = new ClprConnectorKey(Bytes.wrap(CHANNEL_ID), CONNECTOR_ID_BYTES);
        given(connectorStore.getConnector(key)).willReturn(connector);
    }

    private void givenAuthorizationResult(final Bytes result) {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.configuration()).willReturn(DEFAULT_CONFIG);
        final var callData = SendMessageCall.encodeAuthorizeOutboundMessage(
                CHANNEL_ID, TARGET_APP, SENDER_ADDRESS.getBytes().toArray(), MESSAGE_DATA);
        given(nativeOperations.dispatchReadonlyContractCall(
                        eq(entityIdFactory.newAccountId(DEFAULT_ACCOUNTS_CONFIG.systemAdmin())),
                        eq(CONNECTOR_CONTRACT_ID),
                        aryEq(callData),
                        eq(50_000L),
                        assertArg(dispatchMetadata -> {
                            assertThat(dispatchMetadata.getMetadata(CLPR_DISPATCH, ClprDispatchMetadata.class))
                                    .hasValue(
                                            new ClprDispatchMetadata(CLPR_SERVICE_ACCOUNT_ID, CLPR_EVM_ADDRESS_BYTES));
                            assertThat(dispatchMetadata.getMetadata(STATIC_CALL, Boolean.class))
                                    .hasValue(Boolean.TRUE);
                        })))
                .willReturn(result);
    }

    @Test
    @DisplayName(
            "should return success with message ID when authorizeOutboundMessage returns true and sendMessage succeeds")
    void successReturnsMessageId() {
        givenConnectorLookup(connectorWithContract());
        givenAuthorizationResult(BOOL_TRUE_RESULT);
        given(storeFactory.serviceApi(ClprServiceApi.class)).willReturn(clprApi);
        given(clprApi.sendMessage(
                        Bytes.wrap(CHANNEL_ID),
                        CONNECTOR_ID_BYTES,
                        Bytes.wrap(TARGET_APP),
                        Bytes.wrap(SENDER_ADDRESS.getBytes().toArray()),
                        Bytes.wrap(MESSAGE_DATA)))
                .willReturn(42L);

        final var result = createSubject().execute(frame);

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
    }

    @Test
    @DisplayName("should revert with CLPR_AUTHORIZATION_FAILED when authorizeOutboundMessage returns false")
    void revertWhenAuthorizeFalse() {
        givenConnectorLookup(connectorWithContract());
        givenAuthorizationResult(BOOL_FALSE_RESULT);

        final var result = createSubject().execute(frame);

        assertThat(result.responseCode()).isEqualTo(CLPR_AUTHORIZATION_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
        verify(storeFactory, never()).serviceApi(ClprServiceApi.class);
    }

    @Test
    @DisplayName("should revert with CLPR_AUTHORIZATION_FAILED when authorizeOutboundMessage reverts (null result)")
    void revertWhenAuthorizeReverts() {
        givenConnectorLookup(connectorWithContract());
        givenAuthorizationResult(null);

        final var result = createSubject().execute(frame);

        assertThat(result.responseCode()).isEqualTo(CLPR_AUTHORIZATION_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
        verify(storeFactory, never()).serviceApi(ClprServiceApi.class);
    }

    @Test
    @DisplayName("should revert with CLPR_AUTHORIZATION_FAILED when connector not found")
    void revertWhenConnectorNotFound() {
        givenConnectorLookup(null);

        final var result = createSubject().execute(frame);

        assertThat(result.responseCode()).isEqualTo(CLPR_AUTHORIZATION_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
    }

    @Test
    @DisplayName("should revert with error code when sendMessage throws HandleException")
    void revertOnHandleException() {
        givenConnectorLookup(connectorWithContract());
        givenAuthorizationResult(BOOL_TRUE_RESULT);
        given(storeFactory.serviceApi(ClprServiceApi.class)).willReturn(clprApi);
        given(clprApi.sendMessage(
                        Bytes.wrap(CHANNEL_ID),
                        CONNECTOR_ID_BYTES,
                        Bytes.wrap(TARGET_APP),
                        Bytes.wrap(SENDER_ADDRESS.getBytes().toArray()),
                        Bytes.wrap(MESSAGE_DATA)))
                .willThrow(new HandleException(CLPR_CHANNEL_NOT_FOUND));

        final var result = createSubject().execute(frame);

        assertThat(result.responseCode()).isEqualTo(CLPR_CHANNEL_NOT_FOUND);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
    }

    @Test
    @DisplayName("should not allow static frame")
    void doesNotAllowStaticFrame() {
        assertThat(createSubject().allowsStaticFrame()).isFalse();
    }

    @Test
    @DisplayName("encodeAuthorizeOutboundMessage matches ABI encoding")
    void encodeAuthorizeOutboundMessageMatchesAbi() {
        final var function = new Function("authorizeOutboundMessage(bytes32,bytes,bytes,bytes)", "(bool)");
        final var expected = function.encodeCall(Tuple.of(
                        CHANNEL_ID, TARGET_APP, SENDER_ADDRESS.getBytes().toArray(), MESSAGE_DATA))
                .array();

        assertThat(SendMessageCall.encodeAuthorizeOutboundMessage(
                        CHANNEL_ID, TARGET_APP, SENDER_ADDRESS.getBytes().toArray(), MESSAGE_DATA))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("decodeBoolResult returns true for a true ABI word")
    void decodeBoolResultTrue() {
        assertThat(SendMessageCall.decodeBoolResult(BOOL_TRUE_RESULT)).isTrue();
    }

    @Test
    @DisplayName("decodeBoolResult returns false for a false ABI word")
    void decodeBoolResultFalse() {
        assertThat(SendMessageCall.decodeBoolResult(BOOL_FALSE_RESULT)).isFalse();
    }

    @Test
    @DisplayName("decodeBoolResult returns false for output shorter than 32 bytes")
    void decodeBoolResultShort() {
        assertThat(SendMessageCall.decodeBoolResult(Bytes.wrap(new byte[4]))).isFalse();
    }

    private SendMessageCall createSubject() {
        return new SendMessageCall(
                mockEnhancement(),
                gasCalculator,
                SENDER_ID,
                SENDER_ADDRESS,
                CHANNEL_ID,
                CONNECTOR_ID,
                TARGET_APP,
                MESSAGE_DATA);
    }
}
