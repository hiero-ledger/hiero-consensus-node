/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.consensus.impl.test.handlers;

import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;

/**
 * Util class used in unit tests for Consensus Service
 */
public final class ConsensusTestUtils {

    static final Key SIMPLE_KEY_A = Key.newBuilder()
            .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes()))
            .build();
    static final Key SIMPLE_KEY_B = Key.newBuilder()
            .ed25519(Bytes.wrap("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".getBytes()))
            .build();
    static final Key A_NONNULL_KEY = Key.DEFAULT;
    static final Key EMPTY_KEYLIST = Key.newBuilder().keyList(KeyList.DEFAULT).build();
    static final Key EMPTY_THRESHOLD_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder().keys(Key.DEFAULT, Key.DEFAULT).build())
                    .build())
            .build();

    private ConsensusTestUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    static Key mockPayerLookup(Key key, AccountID accountId, ReadableAccountStore accountStore) {
        final var account = mock(Account.class);
        given(accountStore.getAccountById(accountId)).willReturn(account);
        given(account.key()).willReturn(key);
        return key;
    }

    static void mockTopicLookup(Key adminKey, Key submitKey, ReadableTopicStore topicStore) {
        given(topicStore.getTopic(notNull()))
                .willReturn(newTopic(adminKey != null ? adminKey : null, submitKey != null ? submitKey : null));
    }

    static Topic newTopic(Key admin, Key submit) {
        return new Topic(
                TopicID.newBuilder().topicNum(123L).build(),
                -1L,
                0L,
                -1L,
                AccountID.newBuilder().accountNum(1234567L).build(),
                false,
                null,
                "memo",
                admin,
                submit,
                null,
                null,
                null);
    }
}
