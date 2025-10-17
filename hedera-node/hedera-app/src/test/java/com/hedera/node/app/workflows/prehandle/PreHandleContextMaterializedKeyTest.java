// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.prehandle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.util.HapiUtils.EMPTY_KEY_LIST;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PreHandleContextMaterializedKeyTest {

    private static final Configuration CONFIG = HederaTestConfigBuilder.createConfig();

    private final AccountID payerId = AccountID.newBuilder().accountNum(1001L).build();
    private final AccountID targetId = AccountID.newBuilder().accountNum(2002L).build();

    private final Key payerKey = Key.newBuilder()
            .ed25519(Bytes.wrap("pppppppppppppppppppppppppppppppp"))
            .build();
    private final Key templateKey = Key.newBuilder()
            .ed25519(Bytes.wrap("tttttttttttttttttttttttttttttttt"))
            .build();
    private final Key materializedKey = Key.newBuilder()
            .ed25519(Bytes.wrap("mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm"))
            .build();

    @Mock
    private ReadableStoreFactory storeFactory;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private Account payerAccount;

    @Mock
    private Account targetAccount;

    @Mock
    private TransactionDispatcher dispatcher;

    @Mock
    private TransactionChecker transactionChecker;

    @Mock
    private NodeInfo creatorInfo;

    private PreHandleContext subject;

    @BeforeEach
    void setup() throws PreCheckException {
        given(storeFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountById(payerId)).willReturn(payerAccount);
        given(payerAccount.keyOrThrow()).willReturn(payerKey);
        subject = new PreHandleContextImpl(
                storeFactory, createTxn(), CONFIG, dispatcher, transactionChecker, creatorInfo);
    }

    @Test
    void requiresMaterializedKeyWhenPresent() throws PreCheckException {
        // Given an account with a template key and a materialized key
        given(accountStore.getAccountById(targetId)).willReturn(targetAccount);
        given(targetAccount.accountIdOrThrow()).willReturn(targetId);
        given(targetAccount.materializedKey()).willReturn(materializedKey);
        given(targetAccount.keyOrElse(EMPTY_KEY_LIST)).willReturn(templateKey);

        // When requiring the key for the target account
        subject.requireKeyOrThrow(targetId, INVALID_ACCOUNT_ID);

        // Then the materialized key is required (not the template key)
        assertIterableEquals(Set.of(materializedKey), subject.requiredNonPayerKeys());
    }

    @Test
    void fallsBackToTemplateKeyWhenNoMaterializedKey() throws PreCheckException {
        // Given an account with only a template key
        given(accountStore.getAccountById(targetId)).willReturn(targetAccount);
        given(targetAccount.accountIdOrThrow()).willReturn(targetId);
        given(targetAccount.materializedKey()).willReturn(null);
        given(targetAccount.keyOrThrow()).willReturn(templateKey);
        given(targetAccount.keyOrElse(EMPTY_KEY_LIST)).willReturn(templateKey);

        // When requiring the key for the target account
        subject.requireKeyOrThrow(targetId, INVALID_ACCOUNT_ID);

        // Then the template key is required
        assertIterableEquals(Set.of(templateKey), subject.requiredNonPayerKeys());
    }

    private TransactionBody createTxn() {
        final var transactionID = TransactionID.newBuilder().accountID(payerId);
        final var body = CryptoCreateTransactionBody.newBuilder().memo("Create").build();
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .cryptoCreateAccount(body)
                .build();
    }
}
