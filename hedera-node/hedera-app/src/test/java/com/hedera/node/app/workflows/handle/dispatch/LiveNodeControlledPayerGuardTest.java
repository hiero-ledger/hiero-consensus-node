// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.dispatch;

import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.INTERNAL_SYSTEM_TRANSACTION;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.SYSTEM_TXN_CREATION_ENTITY_NUM;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LiveNodeControlledPayerGuardTest {
    private static final AccountID CREATOR_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(3).build();
    private static final AccountID FOREIGN_PAYER_ID =
            AccountID.newBuilder().accountNum(1_234).build();
    // 0.0.50 is the default systemAdmin account (see AccountsConfig)
    private static final AccountID SYSTEM_ADMIN_ID =
            AccountID.newBuilder().accountNum(50).build();

    @Mock
    private Dispatch dispatch;

    @Mock
    private NodeInfo creatorInfo;

    @Mock
    private HandleContext handleContext;

    private final LiveNodeControlledPayerGuard subject = new LiveNodeControlledPayerGuard();

    @Test
    void rejectsNodeCategoryForeignPayer() {
        given(dispatch.txnCategory()).willReturn(HandleContext.TransactionCategory.NODE);
        given(dispatch.payerId()).willReturn(FOREIGN_PAYER_ID);
        givenCreatorInfo();
        given(dispatch.config()).willReturn(HederaTestConfigBuilder.createConfig());

        assertTrue(subject.rejectsForeignNodePayer(dispatch));
    }

    @Test
    void allowsNodeCategoryCreatorPayer() {
        given(dispatch.txnCategory()).willReturn(HandleContext.TransactionCategory.NODE);
        given(dispatch.payerId()).willReturn(CREATOR_ACCOUNT_ID);
        givenCreatorInfo();

        assertFalse(subject.rejectsForeignNodePayer(dispatch));
    }

    @Test
    void rejectsGossipedSystemAdminPayer() {
        givenSystemAdminDispatch(DispatchMetadata.EMPTY_METADATA);

        assertTrue(subject.rejectsForeignNodePayer(dispatch));
    }

    @Test
    void rejectsSystemAdminPayerWithFalseInternalMarker() {
        givenSystemAdminDispatch(new DispatchMetadata(INTERNAL_SYSTEM_TRANSACTION, false));

        assertTrue(subject.rejectsForeignNodePayer(dispatch));
    }

    @Test
    void entityCreationMetadataDoesNotAuthorizeSystemAdminPayer() {
        givenSystemAdminDispatch(new DispatchMetadata(SYSTEM_TXN_CREATION_ENTITY_NUM, 0L));

        assertTrue(subject.rejectsForeignNodePayer(dispatch));
    }

    @Test
    void allowsTrustedInternalSystemAdminPayer() {
        givenSystemAdminDispatch(new DispatchMetadata(INTERNAL_SYSTEM_TRANSACTION, true));

        assertFalse(subject.rejectsForeignNodePayer(dispatch));
    }

    @Test
    void internalMarkerDoesNotAuthorizeForeignPayer() {
        given(dispatch.txnCategory()).willReturn(HandleContext.TransactionCategory.NODE);
        given(dispatch.payerId()).willReturn(FOREIGN_PAYER_ID);
        givenCreatorInfo();
        given(dispatch.config()).willReturn(HederaTestConfigBuilder.createConfig());
        // The marker must not grant authority over any account other than the configured system admin.
        lenient().when(dispatch.handleContext()).thenReturn(handleContext);
        lenient()
                .when(handleContext.dispatchMetadata())
                .thenReturn(new DispatchMetadata(INTERNAL_SYSTEM_TRANSACTION, true));

        assertTrue(subject.rejectsForeignNodePayer(dispatch));
    }

    @Test
    void honorsConfiguredSystemAdminAndLedger() {
        given(dispatch.txnCategory()).willReturn(HandleContext.TransactionCategory.NODE);
        given(dispatch.payerId())
                .willReturn(AccountID.newBuilder()
                        .shardNum(1)
                        .realmNum(2)
                        .accountNum(60)
                        .build());
        givenCreatorInfo();
        given(dispatch.config())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("hedera.shard", 1)
                        .withValue("hedera.realm", 2)
                        .withValue("accounts.systemAdmin", 60)
                        .getOrCreateConfig());
        given(dispatch.handleContext()).willReturn(handleContext);
        given(handleContext.dispatchMetadata()).willReturn(new DispatchMetadata(INTERNAL_SYSTEM_TRANSACTION, true));

        assertFalse(subject.rejectsForeignNodePayer(dispatch));
    }

    @Test
    void doesNotApplyToNonNodeCategory() {
        // The guard only concerns NODE-category dispatches (which skip payer-sig verification); a foreign payer on
        // any other category is not this guard's business, so it never rejects.
        given(dispatch.txnCategory()).willReturn(HandleContext.TransactionCategory.USER);

        assertFalse(subject.rejectsForeignNodePayer(dispatch));
    }

    private void givenSystemAdminDispatch(final DispatchMetadata metadata) {
        given(dispatch.txnCategory()).willReturn(HandleContext.TransactionCategory.NODE);
        given(dispatch.payerId()).willReturn(SYSTEM_ADMIN_ID);
        givenCreatorInfo();
        given(dispatch.config()).willReturn(HederaTestConfigBuilder.createConfig());
        given(dispatch.handleContext()).willReturn(handleContext);
        given(handleContext.dispatchMetadata()).willReturn(metadata);
    }

    private void givenCreatorInfo() {
        given(dispatch.creatorInfo()).willReturn(creatorInfo);
        given(creatorInfo.accountId()).willReturn(CREATOR_ACCOUNT_ID);
    }
}
