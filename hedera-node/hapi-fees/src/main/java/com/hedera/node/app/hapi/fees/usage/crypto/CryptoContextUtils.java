// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import com.hederahashgraph.api.proto.java.CryptoAllowance;
import com.hederahashgraph.api.proto.java.GrantedCryptoAllowance;
import com.hederahashgraph.api.proto.java.GrantedNftAllowance;
import com.hederahashgraph.api.proto.java.GrantedTokenAllowance;
import com.hederahashgraph.api.proto.java.NftAllowance;
import com.hederahashgraph.api.proto.java.TokenAllowance;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CryptoContextUtils {
    private CryptoContextUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static Map<Long, Long> convertToCryptoMapFromGranted(final List<GrantedCryptoAllowance> allowances) {
        Map<Long, Long> allowanceMap = new HashMap<>();
        for (var a : allowances) {
            allowanceMap.put(a.getSpender().getAccountNum(), a.getAmount());
        }
        return allowanceMap;
    }

    public static Map<AllowanceId, Long> convertToTokenMapFromGranted(final List<GrantedTokenAllowance> allowances) {
        Map<AllowanceId, Long> allowanceMap = new HashMap<>();
        for (var a : allowances) {
            allowanceMap.put(
                    new AllowanceId(a.getTokenId().getTokenNum(), a.getSpender().getAccountNum()), a.getAmount());
        }
        return allowanceMap;
    }

    public static Set<AllowanceId> convertToNftMapFromGranted(final List<GrantedNftAllowance> allowances) {
        Set<AllowanceId> approveForAllAllowances = new HashSet<>();
        for (var a : allowances) {
            approveForAllAllowances.add(
                    new AllowanceId(a.getTokenId().getTokenNum(), a.getSpender().getAccountNum()));
        }
        return approveForAllAllowances;
    }

    public static Map<Long, Long> convertToCryptoMap(final List<CryptoAllowance> allowances) {
        Map<Long, Long> allowanceMap = new HashMap<>();
        for (var a : allowances) {
            allowanceMap.put(a.getSpender().getAccountNum(), a.getAmount());
        }
        return allowanceMap;
    }

    public static Map<AllowanceId, Long> convertToTokenMap(final List<TokenAllowance> allowances) {
        Map<AllowanceId, Long> allowanceMap = new HashMap<>();
        for (var a : allowances) {
            allowanceMap.put(
                    new AllowanceId(a.getTokenId().getTokenNum(), a.getSpender().getAccountNum()), a.getAmount());
        }
        return allowanceMap;
    }

    public static Set<AllowanceId> convertToNftMap(final List<NftAllowance> allowances) {
        Set<AllowanceId> allowanceMap = new HashSet<>();
        for (var a : allowances) {
            allowanceMap.add(
                    new AllowanceId(a.getTokenId().getTokenNum(), a.getSpender().getAccountNum()));
        }
        return allowanceMap;
    }

    public static int countSerials(final List<NftAllowance> nftAllowancesList) {
        int totalSerials = 0;
        for (var allowance : nftAllowancesList) {
            totalSerials += allowance.getSerialNumbersCount();
        }
        return totalSerials;
    }

    static int getChangedCryptoKeys(final Set<Long> newKeys, final Set<Long> existingKeys) {
        int counter = 0;
        for (var key : newKeys) {
            if (!existingKeys.contains(key)) {
                counter++;
            }
        }
        return counter;
    }

    static int getChangedTokenKeys(final Set<AllowanceId> newKeys, final Set<AllowanceId> existingKeys) {
        int counter = 0;
        for (var key : newKeys) {
            if (!existingKeys.contains(key)) {
                counter++;
            }
        }
        return counter;
    }
}
