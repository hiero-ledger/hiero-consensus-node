// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import com.hedera.hapi.node.token.CryptoAllowance;
import com.hedera.hapi.node.token.GrantedCryptoAllowance;
import com.hedera.hapi.node.token.GrantedNftAllowance;
import com.hedera.hapi.node.token.GrantedTokenAllowance;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.hapi.node.token.TokenAllowance;
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
            allowanceMap.put(a.spender().accountNum(), a.amount());
        }
        return allowanceMap;
    }

    public static Map<AllowanceId, Long> convertToTokenMapFromGranted(final List<GrantedTokenAllowance> allowances) {
        Map<AllowanceId, Long> allowanceMap = new HashMap<>();
        for (var a : allowances) {
            allowanceMap.put(new AllowanceId(a.tokenId().tokenNum(), a.spender().accountNum()), a.amount());
        }
        return allowanceMap;
    }

    public static Set<AllowanceId> convertToNftMapFromGranted(final List<GrantedNftAllowance> allowances) {
        Set<AllowanceId> approveForAllAllowances = new HashSet<>();
        for (var a : allowances) {
            approveForAllAllowances.add(
                    new AllowanceId(a.tokenId().tokenNum(), a.spender().accountNum()));
        }
        return approveForAllAllowances;
    }

    public static Map<Long, Long> convertToCryptoMap(final List<CryptoAllowance> allowances) {
        Map<Long, Long> allowanceMap = new HashMap<>();
        for (var a : allowances) {
            allowanceMap.put(a.spender().accountNum(), a.amount());
        }
        return allowanceMap;
    }

    public static Map<AllowanceId, Long> convertToTokenMap(final List<TokenAllowance> allowances) {
        Map<AllowanceId, Long> allowanceMap = new HashMap<>();
        for (var a : allowances) {
            allowanceMap.put(new AllowanceId(a.tokenId().tokenNum(), a.spender().accountNum()), a.amount());
        }
        return allowanceMap;
    }

    public static Set<AllowanceId> convertToNftMap(final List<NftAllowance> allowances) {
        Set<AllowanceId> allowanceMap = new HashSet<>();
        for (var a : allowances) {
            allowanceMap.add(new AllowanceId(a.tokenId().tokenNum(), a.spender().accountNum()));
        }
        return allowanceMap;
    }

    public static long countSerials(final List<NftAllowance> nftAllowancesList) {
        long totalSerials = 0;
        for (var allowance : nftAllowancesList) {
            totalSerials += allowance.serialNumbers().size();
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
