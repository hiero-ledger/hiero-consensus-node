// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.EvmHookCall;
import com.hedera.hapi.node.base.HookCall;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.hooks.HookCreation;
import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.hapi.node.hooks.HookDispatchTransactionBody;
import com.hedera.hapi.node.hooks.HookExecution;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.ContractServiceComponent;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent;
import com.hedera.node.app.service.contract.impl.exec.gas.HederaGasCalculator;
import com.hedera.node.app.service.contract.impl.handlers.HookDispatchHandler;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.spi.key.KeyVerifier;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.config.data.ApiPermissionConfig;
import com.hedera.node.config.data.HooksConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Regression tests for HookDispatchHandler ownership checks on CREATION/DELETION.
 * An attacker must not be able to attach or remove a hook on a victim entity without
 * the victim's key, even if api-permission.hookDispatch is widened.
 */
@ExtendWith(MockitoExtension.class)
class HookDispatchOwnershipTest extends ContractHandlerTestBase {
    private static final AccountID ATTACKER =
            AccountID.newBuilder().accountNum(9_999L).build();
    private static final AccountID VICTIM =
            AccountID.newBuilder().accountNum(7_777L).build();

    @Mock
    private HandleContext handleContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private HookDispatchStreamBuilder recordBuilder;

    @Mock
    private HandleContext.SavepointStack savepointStack;

    @Mock
    protected WritableEvmHookStore evmHookStore;

    @Mock
    protected AttributeValidator attributeValidator;

    @Mock
    private TransactionComponent.Factory factory;

    @Mock
    private HederaGasCalculator gasCalculator;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ContractServiceComponent contractServiceComponent;

    @Mock
    private KeyVerifier keyVerifier;

    @Mock
    private SignatureVerification signatureVerification;

    @Mock
    private Account victimAccount;

    @Mock
    private PreHandleContext preHandleContext;

    private final Configuration config = HederaTestConfigBuilder.create()
            .withValue("hooks.hooksEnabled", true)
            .getOrCreateConfig();

    private HookDispatchHandler subject;

    @BeforeEach
    void setUp() {
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient().when(storeFactory.writableStore(WritableEvmHookStore.class)).thenReturn(evmHookStore);
        lenient().when(storeFactory.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
        lenient().when(handleContext.attributeValidator()).thenReturn(attributeValidator);
        lenient().when(handleContext.configuration()).thenReturn(config);
        lenient().when(handleContext.savepointStack()).thenReturn(savepointStack);
        lenient().when(savepointStack.getBaseBuilder(HookDispatchStreamBuilder.class)).thenReturn(recordBuilder);
        lenient().when(handleContext.keyVerifier()).thenReturn(keyVerifier);
        lenient().when(keyVerifier.verificationFor(any())).thenReturn(signatureVerification);
        lenient().when(accountStore.getAccountById(VICTIM)).thenReturn(victimAccount);
        lenient().when(victimAccount.hasKey()).thenReturn(true);
        lenient().when(victimAccount.key()).thenReturn(adminKey);
        lenient().when(victimAccount.keyOrThrow()).thenReturn(adminKey);
        lenient()
                .when(gasCalculator.transactionGasRequirements(0, 0, false, null, null))
                .thenReturn(TestHelpers.gasChargesFromIntrinsicGas(
                        config.getConfigData(HooksConfig.class).evmHookIntrinsicGasCost()));
        subject = new HookDispatchHandler(() -> factory, gasCalculator, entityIdFactory, contractServiceComponent);
    }

    @Test
    void creationOnVictimFailsWithoutOwnerSignature() {
        given(handleContext.body()).willReturn(creationBody(ATTACKER, VICTIM));
        given(signatureVerification.passed()).willReturn(false);

        final var ex = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_SIGNATURE, ex.getStatus());
        verify(evmHookStore, never()).createEvmHook(any(), anyLong());
    }

    @Test
    void creationOnVictimSucceedsWhenOwnerAuthorized() {
        given(handleContext.body()).willReturn(creationBody(ATTACKER, VICTIM));
        given(signatureVerification.passed()).willReturn(true);

        assertDoesNotThrow(() -> subject.handle(handleContext));
        verify(evmHookStore).createEvmHook(any(), anyLong());
    }

    @Test
    void deletionOnVictimFailsWithoutOwnerSignature() {
        given(handleContext.body()).willReturn(deletionBody(ATTACKER, VICTIM, 42L));
        given(signatureVerification.passed()).willReturn(false);

        final var ex = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_SIGNATURE, ex.getStatus());
        verify(evmHookStore, never()).remove(any());
        verify(evmHookStore, never()).getEvmHook(any());
    }

    @Test
    void creationFailsWhenTargetEntityMissing() {
        given(handleContext.body()).willReturn(creationBody(ATTACKER, VICTIM));
        given(accountStore.getAccountById(VICTIM)).willReturn(null);

        final var ex = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_ACCOUNT_ID, ex.getStatus());
        verify(evmHookStore, never()).createEvmHook(any(), anyLong());
    }

    @Test
    void preHandleRequiresOwnerKeyForCreation() throws Exception {
        given(preHandleContext.body()).willReturn(creationBody(ATTACKER, VICTIM));

        subject.preHandle(preHandleContext);

        verify(preHandleContext).requireKeyOrThrow(VICTIM, ResponseCodeEnum.INVALID_ACCOUNT_ID);
    }

    @Test
    void preHandleRequiresOwnerKeyForDeletion() throws Exception {
        given(preHandleContext.body()).willReturn(deletionBody(ATTACKER, VICTIM, 42L));

        subject.preHandle(preHandleContext);

        verify(preHandleContext).requireKeyOrThrow(VICTIM, ResponseCodeEnum.INVALID_ACCOUNT_ID);
    }

    @Test
    void preHandleIsNoOpForExecution() throws Exception {
        given(preHandleContext.body()).willReturn(executionBody(ATTACKER, VICTIM, 999L));

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
        verify(preHandleContext, never()).requireKeyOrThrow(any(AccountID.class), any());
    }

    @Test
    void executionOfMissingHookFailsClosedWithHookNotFound() {
        given(handleContext.body()).willReturn(executionBody(ATTACKER, VICTIM, 999L));
        given(evmHookStore.getEvmHook(any())).willReturn(null);

        final var ex = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.HOOK_NOT_FOUND, ex.getStatus());
        verify(factory, never()).create(any(), any(), any());
    }

    @Test
    void defaultApiPermissionBlocksAllUserAccountsFromHookDispatch() {
        final var perms = config.getConfigData(ApiPermissionConfig.class);
        final var range = perms.hookDispatch();
        assertEquals(false, range.contains(ATTACKER.accountNumOrThrow()));
        assertEquals(false, range.contains(VICTIM.accountNumOrThrow()));
        assertEquals(true, range.contains(0L), "Only account 0 permitted by default (0-0)");
    }

    private TransactionBody creationBody(AccountID payerId, AccountID targetEntity) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(payerId)
                        .transactionValidStart(consensusTimestamp)
                        .build())
                .hookDispatch(HookDispatchTransactionBody.newBuilder()
                        .creation(HookCreation.newBuilder()
                                .entityId(HookEntityId.newBuilder()
                                        .accountId(targetEntity)
                                        .build())
                                .details(HookCreationDetails.newBuilder()
                                        .hookId(42L)
                                        .adminKey(adminKey)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private TransactionBody deletionBody(AccountID payerId, AccountID targetEntity, long hookId) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(payerId)
                        .transactionValidStart(consensusTimestamp)
                        .build())
                .hookDispatch(HookDispatchTransactionBody.newBuilder()
                        .hookIdToDelete(new HookId(
                                HookEntityId.newBuilder().accountId(targetEntity).build(), hookId))
                        .build())
                .build();
    }

    private TransactionBody executionBody(AccountID payerId, AccountID targetEntity, long hookId) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(payerId)
                        .transactionValidStart(consensusTimestamp)
                        .build())
                .hookDispatch(HookDispatchTransactionBody.newBuilder()
                        .execution(HookExecution.newBuilder()
                                .hookEntityId(HookEntityId.newBuilder()
                                        .accountId(targetEntity)
                                        .build())
                                .call(HookCall.newBuilder()
                                        .hookId(hookId)
                                        .evmHookCall(EvmHookCall.newBuilder()
                                                .gasLimit(100_000L)
                                                .data(Bytes.EMPTY)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
    }
}
