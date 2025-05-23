// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.classiccalls;

import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;

import com.esaulpaugh.headlong.abi.Address;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.swirlds.base.utility.Pair;
import java.util.Set;

public class ClassicInventory {
    public static final String ALICE = "Alice";
    public static final String BOB = "Bob";
    public static final String ALPHA = "Alpha";
    public static final String BRAVO = "Bravo";
    public static final String ZERO = "Zero";
    public static final String ONE = "One";
    public static final String CRYPTO_KEY = "cryptoKey";
    public static final String FAILABLE_CALLS_CONTRACT = "FailableClassicCalls";
    public static final String FAILABLE_CONTROL_KEY = "authorizedContractKey";
    public static final Address INVALID_TOKEN_ADDRESS =
            idAsHeadlongAddress(TokenID.newBuilder().setTokenNum(Long.MAX_VALUE).build());
    public static final Address INVALID_ACCOUNT_ADDRESS = idAsHeadlongAddress(
            AccountID.newBuilder().setAccountNum(Long.MAX_VALUE).build());
    public static final String[] VALID_ACCOUNT_IDS = new String[] {ALICE, BOB};

    public static final String[] VALID_FUNGIBLE_TOKEN_IDS = new String[] {ALPHA, BRAVO};

    public static final String[] VALID_NON_FUNGIBLE_TOKEN_IDS = new String[] {ZERO, ONE};

    public static final Set<Pair<String, String>> VALID_ASSOCIATIONS =
            Set.of(Pair.of(ALICE, ALPHA), Pair.of(ALICE, ZERO), Pair.of(BOB, BRAVO), Pair.of(BOB, ONE));
}
