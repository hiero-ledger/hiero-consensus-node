// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec;

import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import java.util.stream.Stream;

public interface PropertySource {
    String get(String property);

    default AccountID getAccount(String property) {
        try {
            return asAccount(get(property));
        } catch (Exception ignore) {
        }
        return AccountID.getDefaultInstance();
    }

    default ContractID getContract(String property) {
        try {
            return asContract(get(property));
        } catch (Exception ignore) {
        }
        return ContractID.getDefaultInstance();
    }

    default int getInteger(String property) {
        return Integer.parseInt(get(property));
    }

    default byte[] getBytes(String property) {
        return get(property).getBytes();
    }

    static AccountID asAccount(String v) {
        long[] nativeParts = asDotDelimitedLongArray(v);
        return AccountID.newBuilder()
                .setShardNum(nativeParts[0])
                .setRealmNum(nativeParts[1])
                .setAccountNum(nativeParts[2])
                .build();
    }

    static String asAccountString(AccountID account) {
        return String.format("%d.%d.%d", account.getShardNum(), account.getRealmNum(), account.getAccountNum());
    }

    static ContractID asContract(String v) {
        long[] nativeParts = asDotDelimitedLongArray(v);
        return ContractID.newBuilder()
                .setShardNum(nativeParts[0])
                .setRealmNum(nativeParts[1])
                .setContractNum(nativeParts[2])
                .build();
    }

    static long[] asDotDelimitedLongArray(String s) {
        String[] parts = s.split("[.]");
        return Stream.of(parts).mapToLong(Long::valueOf).toArray();
    }
}
