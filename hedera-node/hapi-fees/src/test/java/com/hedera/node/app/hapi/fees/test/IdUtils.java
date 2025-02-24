// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.test;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenBalance;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import java.util.stream.Stream;

public class IdUtils {
    public static TokenID tokenWith(final long num) {
        return TokenID.newBuilder().shardNum(0).realmNum(0).tokenNum(num).build();
    }

    public static TopicID asTopic(final String v) {
        final long[] nativeParts = asDotDelimitedLongArray(v);
        return TopicID.newBuilder()
                .shardNum(nativeParts[0])
                .realmNum(nativeParts[1])
                .topicNum(nativeParts[2])
                .build();
    }

    public static AccountID asAccount(final String v) {
        final long[] nativeParts = asDotDelimitedLongArray(v);
        return AccountID.newBuilder()
                .shardNum(nativeParts[0])
                .realmNum(nativeParts[1])
                .accountNum(nativeParts[2])
                .build();
    }

    public static ContractID asContract(final String v) {
        final long[] nativeParts = asDotDelimitedLongArray(v);
        return ContractID.newBuilder()
                .shardNum(nativeParts[0])
                .realmNum(nativeParts[1])
                .contractNum(nativeParts[2])
                .build();
    }

    public static FileID asFile(final String v) {
        final long[] nativeParts = asDotDelimitedLongArray(v);
        return FileID.newBuilder()
                .shardNum(nativeParts[0])
                .realmNum(nativeParts[1])
                .fileNum(nativeParts[2])
                .build();
    }

    public static TokenID asToken(final String v) {
        final long[] nativeParts = asDotDelimitedLongArray(v);
        return TokenID.newBuilder()
                .shardNum(nativeParts[0])
                .realmNum(nativeParts[1])
                .tokenNum(nativeParts[2])
                .build();
    }

    public static ScheduleID asSchedule(final String v) {
        final long[] nativeParts = asDotDelimitedLongArray(v);
        return ScheduleID.newBuilder()
                .shardNum(nativeParts[0])
                .realmNum(nativeParts[1])
                .scheduleNum(nativeParts[2])
                .build();
    }

    static long[] asDotDelimitedLongArray(final String s) {
        final String[] parts = s.split("[.]");
        return Stream.of(parts).mapToLong(Long::valueOf).toArray();
    }

    public static NftID asNftID(final String v, final long serialNum) {
        final var tokenID = asToken(v);

        return NftID.newBuilder().tokenId(tokenID).serialNumber(serialNum).build();
    }

    public static String asAccountString(final AccountID account) {
        return String.format("%d.%d.%d", account.shardNum(), account.realmNum(), account.accountNum());
    }

    public static TokenBalance tokenBalanceWith(final long id, final long balance) {
        return TokenBalance.newBuilder()
                .tokenId(IdUtils.asToken("0.0." + id))
                .balance(balance)
                .build();
    }

    public static TokenBalance tokenBalanceWith(final TokenID id, final long balance) {
        return TokenBalance.newBuilder().tokenId(id).balance(balance).build();
    }
}
