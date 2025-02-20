// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for tests to validate bulk operations fees
 */
public class BulkOperationsBase {
    protected static final String OWNER = "owner";
    protected static final String RECEIVER = "receiver";
    protected static final String ASSOCIATE_ACCOUNT = "associateAccount";

    protected static final String NFT_TOKEN = "nftToken";
    protected static final String NFT_BURN_ONE_TOKEN = "nftBurnOneToken";
    protected static final String NFT_BURN_TOKEN = "nftBurnToken";
    protected static final String FT_TOKEN = "ftToken";

    /**
     * Create tokens and accounts
     *
     * @return array of operations
     */
    protected static SpecOperation[] createTokensAndAccounts() {
        var supplyKey = "supplyKey";
        final var t = new ArrayList<SpecOperation>(List.of(
                newKeyNamed(supplyKey),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).key(supplyKey),
                tokenCreate(NFT_TOKEN)
                        .treasury(OWNER)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(supplyKey)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0),
                tokenCreate(FT_TOKEN)
                        .treasury(OWNER)
                        .tokenType(FUNGIBLE_COMMON)
                        .supplyKey(supplyKey)
                        .initialSupply(1000L),
                tokenCreate(NFT_BURN_TOKEN)
                        .treasury(OWNER)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(supplyKey)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0),
                tokenCreate(NFT_BURN_ONE_TOKEN)
                        .treasury(OWNER)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(supplyKey)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)));

        return t.toArray(new SpecOperation[0]);
    }
}
