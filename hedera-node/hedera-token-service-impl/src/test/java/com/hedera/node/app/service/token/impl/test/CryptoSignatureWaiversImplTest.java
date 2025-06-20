// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.authorization.AuthorizerImpl;
import com.hedera.node.app.authorization.PrivilegesVerifier;
import com.hedera.node.app.service.token.impl.CryptoSignatureWaiversImpl;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoSignatureWaiversImplTest {
    @Mock
    ConfigProvider configProvider;

    Authorizer authorizer;
    private final AccountID somePayer =
            AccountID.newBuilder().accountNum(1_234L).build();
    private final AccountID treasury = AccountID.newBuilder().accountNum(2L).build();
    private final AccountID systemAdmin = AccountID.newBuilder().accountNum(50L).build();

    private CryptoSignatureWaiversImpl subject;

    @BeforeEach
    void setUp() {
        final var versionedConfig =
                new VersionedConfigImpl(HederaTestConfigBuilder.create().getOrCreateConfig(), 1);

        given(configProvider.getConfiguration()).willReturn(versionedConfig);
        authorizer = new AuthorizerImpl(configProvider, new PrivilegesVerifier(configProvider));
        subject = new CryptoSignatureWaiversImpl(authorizer);
    }

    @Test
    void waivesTargetSigForTreasuryUpdateOfNonTreasurySystemAccount() {
        final var txn = cryptoUpdateTransaction(treasury, systemAdmin);
        assertTrue(subject.isTargetAccountSignatureWaived(txn, treasury));
    }

    @Test
    void waivesNewKeySigForTreasuryUpdateOfNonTreasurySystemAccount() {
        final var txn = cryptoUpdateTransaction(treasury, systemAdmin);
        assertTrue(subject.isNewKeySignatureWaived(txn, treasury));
    }

    @Test
    void doesntWaiveNewKeySigForTreasuryUpdateOfTreasurySystemAccount() {
        final var txn = cryptoUpdateTransaction(treasury, treasury);
        assertFalse(subject.isNewKeySignatureWaived(txn, treasury));
    }

    @Test
    void doesntWaiveNewKeySigForTreasuryUpdateOfNonSystemAccount() {
        final var txn = cryptoUpdateTransaction(treasury, somePayer);
        assertFalse(subject.isNewKeySignatureWaived(txn, treasury));
    }

    @Test
    void doesntWaivesNewKeySigForCivilianUpdate() {
        final var txn = cryptoUpdateTransaction(treasury, somePayer);
        assertFalse(subject.isNewKeySignatureWaived(txn, treasury));
    }

    private TransactionBody cryptoUpdateTransaction(final AccountID payerId, final AccountID accountToUpdate) {
        final var transactionID = TransactionID.newBuilder().accountID(payerId);
        final var updateTxnBody = CryptoUpdateTransactionBody.newBuilder()
                .accountIDToUpdate(accountToUpdate)
                .build();
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .cryptoUpdateAccount(updateTxnBody)
                .build();
    }
}
