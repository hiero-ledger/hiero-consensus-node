// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fixtures.ids;

import static java.lang.System.arraycopy;
import static org.hiero.base.utility.CommonUtils.hex;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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

    @Override
    public ContractID newContractIdWithEvmAddress(@NonNull Bytes evmAddress) {
        return ContractID.newBuilder()
                .shardNum(shard)
                .realmNum(realm)
                .evmAddress(evmAddress)
                .build();
    }

    @Override
    public String hexLongZero(long number) {
        final byte[] evmAddress = new byte[20];
        final var shardBytes = Ints.toByteArray((int) shard);
        final var realmBytes = Longs.toByteArray(realm);
        final var numBytes = Longs.toByteArray(number);

        arraycopy(shardBytes, 0, evmAddress, 0, 4);
        arraycopy(realmBytes, 0, evmAddress, 4, 8);
        arraycopy(numBytes, 0, evmAddress, 12, 8);

        return hex(evmAddress);
    }
}
