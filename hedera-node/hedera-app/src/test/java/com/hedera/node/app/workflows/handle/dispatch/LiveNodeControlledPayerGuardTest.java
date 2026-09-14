// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.dispatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.workflows.HandleContext;
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
    void allowsNodeCategorySystemAdminPayer() {
        given(dispatch.txnCategory()).willReturn(HandleContext.TransactionCategory.NODE);
        given(dispatch.payerId()).willReturn(SYSTEM_ADMIN_ID);
        givenCreatorInfo();
        given(dispatch.config()).willReturn(HederaTestConfigBuilder.createConfig());

        assertFalse(subject.rejectsForeignNodePayer(dispatch));
    }

    @Test
    void doesNotApplyToNonNodeCategory() {
        // The guard only concerns NODE-category dispatches (which skip payer-sig verification); a foreign payer on
        // any other category is not this guard's business, so it never rejects.
        given(dispatch.txnCategory()).willReturn(HandleContext.TransactionCategory.USER);

        assertFalse(subject.rejectsForeignNodePayer(dispatch));
    }

    private void givenCreatorInfo() {
        given(dispatch.creatorInfo()).willReturn(creatorInfo);
        given(creatorInfo.accountId()).willReturn(CREATOR_ACCOUNT_ID);
    }
}
