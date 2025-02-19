/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.spi.fixtures.ids;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Fixed shard/realm implementation of {@link EntityIdFactory}.
 */
public class FakeEntityIdFactoryImpl implements EntityIdFactory {
    private final long shard;
    private final long realm;

    public FakeEntityIdFactoryImpl(final long shard, final long realm) {
        this.shard = shard;
        this.realm = realm;
    }

    @Override
    public TokenID newTokenId(long number) {
        return new TokenID(shard, realm, number);
    }

    @Override
    public TopicID newTopicId(long number) {
        return new TopicID(shard, realm, number);
    }

    @Override
    public ScheduleID newScheduleId(long number) {
        return new ScheduleID(shard, realm, number);
    }

    @Override
    public AccountID newAccountId(long number) {
        return AccountID.newBuilder()
                .shardNum(shard)
                .realmNum(realm)
                .accountNum(number)
                .build();
    }

    @Override
    public AccountID newAccountIdWithAlias(@NonNull Bytes alias) {
        return AccountID.newBuilder()
                .shardNum(shard)
                .realmNum(realm)
                .alias(alias)
                .build();
    }

    @Override
    public AccountID newDefaultAccountId() {
        return AccountID.newBuilder().shardNum(shard).realmNum(realm).build();
    }

    @Override
    public FileID newFileId(long number) {
        return new FileID(shard, realm, number);
    }

    @Override
    public ContractID newContractId(long number) {
        return ContractID.newBuilder()
                .shardNum(shard)
                .realmNum(realm)
                .contractNum(number)
                .build();
    }
}
