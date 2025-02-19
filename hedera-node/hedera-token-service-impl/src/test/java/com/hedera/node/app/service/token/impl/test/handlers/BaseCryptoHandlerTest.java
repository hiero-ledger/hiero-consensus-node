/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.token.impl.test.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class BaseCryptoHandlerTest {
    @Mock
    private Configuration configuration;

    @Mock
    private AccountsConfig accountsConfig;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(configuration.getConfigData(AccountsConfig.class)).thenReturn(accountsConfig);
    }

    @Test
    @DisplayName("isStakingAccount Check if account is a staking reward account")
    void isStakingAccount_returnsTrue_whenAccountIsStakingRewardAccount() {
        when(accountsConfig.stakingRewardAccount()).thenReturn(1L);
        assertTrue(BaseCryptoHandler.isStakingAccount(configuration, BaseCryptoHandler.asAccount(0L, 0L, 1L)));
    }

    @Test
    @DisplayName("isStakingAccount Check if account is a node reward account")
    void isStakingAccount_returnsTrue_whenAccountIsNodeRewardAccount() {
        when(accountsConfig.nodeRewardAccount()).thenReturn(1L);
        assertTrue(BaseCryptoHandler.isStakingAccount(configuration, BaseCryptoHandler.asAccount(0L, 0L, 1L)));
    }

    @Test
    @DisplayName("isStakingAccount Check if account is not a staking or node reward account")
    void isStakingAccount_returnsFalse_whenAccountIsNotStakingOrNodeRewardAccount() {
        when(accountsConfig.stakingRewardAccount()).thenReturn(1L);
        when(accountsConfig.nodeRewardAccount()).thenReturn(2L);
        assertFalse(BaseCryptoHandler.isStakingAccount(configuration, BaseCryptoHandler.asAccount(0L, 0L, 3L)));
    }

    @DisplayName("asAccount Check if asAccount returns AccountID with given number")
    @Test
    void asAccountReturnsAccountIDWithGivenNumber() {
        AccountID result = BaseCryptoHandler.asAccount(0L, 0L, 123);
        assertEquals(123, result.accountNum());
    }

    @Test
    @DisplayName("hasAccountNumOrAlias Null accountID is invalid")
    void hasAccountNumOrAlias_returnsFalse_whenAccountIsNull() {
        assertFalse(BaseCryptoHandler.hasAccountNumOrAlias(null));
    }

    @Test
    @DisplayName("hasAccountNumOrAlias Account with number is valid")
    void hasAccountNumOrAlias_returnsTrue_whenAccountHasNumber() {
        AccountID accountID = AccountID.newBuilder().accountNum(1L).build();
        assertTrue(BaseCryptoHandler.hasAccountNumOrAlias(accountID));
    }

    @Test
    @DisplayName("hasAccountNumOrAlias Account with alias is valid")
    void hasAccountNumOrAlias_returnsTrue_whenAccountHasAlias() {
        byte[] bytes = ByteString.copyFromUtf8("alias").toByteArray();
        AccountID accountID = AccountID.newBuilder().alias(Bytes.wrap(bytes)).build();
        assertTrue(BaseCryptoHandler.hasAccountNumOrAlias(accountID));
    }

    @Test
    @DisplayName("hasAccountNumOrAlias Account neither number nor alias is invalid")
    void hasAccountNumOrAlias_returnsFalse_whenAccountHasNeitherNumberNorAlias() {
        AccountID accountID = AccountID.newBuilder().build();
        assertFalse(BaseCryptoHandler.hasAccountNumOrAlias(accountID));
    }
}
