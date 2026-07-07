// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.test;

import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.NftID;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.TokenBalance;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TopicID;
import java.util.stream.Stream;

public class IdUtils {
    public static AccountID asAccount(final long num) {
        return AccountID.newBuilder()
                .setShardNum(0)
                .setRealmNum(0)
                .setAccountNum(num)
                .build();
    }

    public static TokenID asToken(final long num) {
        return tokenWith(num);
    }

    public static ScheduleID asSchedule(final long num) {
        return ScheduleID.newBuilder()
                .setShardNum(0)
                .setRealmNum(0)
                .setScheduleNum(num)
                .build();
    }

    public static NftID asNftID(final long tokenNum, final long serialNum) {
        return NftID.newBuilder()
                .setTokenID(tokenWith(tokenNum))
                .setSerialNumber(serialNum)
                .build();
    }

    public static TokenID tokenWith(final long num) {
        return TokenID.newBuilder()
                .setShardNum(0)
                .setRealmNum(0)
                .setTokenNum(num)
                .build();
    }

    public static TopicID asTopic(final String v) {
        final long[] nativeParts = asDotDelimitedLongArray(v);
        return TopicID.newBuilder()
                .setShardNum(nativeParts[0])
                .setRealmNum(nativeParts[1])
                .setTopicNum(nativeParts[2])
                .build();
    }

    public static AccountID asAccount(final String v) {
        final long[] nativeParts = asDotDelimitedLongArray(v);
        return AccountID.newBuilder()
                .setShardNum(nativeParts[0])
                .setRealmNum(nativeParts[1])
                .setAccountNum(nativeParts[2])
                .build();
    }

    public static ContractID asContract(final String v) {
        final long[] nativeParts = asDotDelimitedLongArray(v);
        return ContractID.newBuilder()
                .setShardNum(nativeParts[0])
                .setRealmNum(nativeParts[1])
                .setContractNum(nativeParts[2])
                .build();
    }

    public static FileID asFile(final String v) {
        final long[] nativeParts = asDotDelimitedLongArray(v);
        return FileID.newBuilder()
                .setShardNum(nativeParts[0])
                .setRealmNum(nativeParts[1])
                .setFileNum(nativeParts[2])
                .build();
    }

    public static TokenID asToken(final String v) {
        final long[] nativeParts = asDotDelimitedLongArray(v);
        return TokenID.newBuilder()
                .setShardNum(nativeParts[0])
                .setRealmNum(nativeParts[1])
                .setTokenNum(nativeParts[2])
                .build();
    }

    public static ScheduleID asSchedule(final String v) {
        final long[] nativeParts = asDotDelimitedLongArray(v);
        return ScheduleID.newBuilder()
                .setShardNum(nativeParts[0])
                .setRealmNum(nativeParts[1])
                .setScheduleNum(nativeParts[2])
                .build();
    }

    static long[] asDotDelimitedLongArray(final String s) {
        final String[] parts = s.split("[.]");
        return Stream.of(parts).mapToLong(Long::valueOf).toArray();
    }

    public static NftID asNftID(final String v, final long serialNum) {
        final var tokenID = asToken(v);

        return NftID.newBuilder().setTokenID(tokenID).setSerialNumber(serialNum).build();
    }

    public static String asAccountString(final AccountID account) {
        return String.format("%d.%d.%d", account.getShardNum(), account.getRealmNum(), account.getAccountNum());
    }

    public static TokenBalance tokenBalanceWith(final long id, final long balance) {
        return TokenBalance.newBuilder()
                .setTokenId(tokenWith(id))
                .setBalance(balance)
                .build();
    }

    public static TokenBalance tokenBalanceWith(final TokenID id, final long balance) {
        return TokenBalance.newBuilder().setTokenId(id).setBalance(balance).build();
    }
}
