// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.queries;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

import com.hedera.services.bdd.spec.SpecOperation;

/**
 * A class with setup for Node Operator Queries tests
 */
public class NodeOperatorQueriesBase {

    // node operator account
    protected static final String NODE_OPERATOR = "operator";
    protected static final String NODE_OPERATOR_KEY = "NODE_OPERATOR_KEY";
    protected static final String FUNGIBLE_QUERY_TOKEN = "fungibleQueryToken";
    protected static final String ANOTHER_FUNGIBLE_QUERY_TOKEN = "anotherFungibleQueryToken";
    protected static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    protected static final String TREASURY = "treasury";
    protected static final String WIPE_KEY = "wipeKey";
    protected static final String SUPPLY_KEY = "supplyKey";
    protected static final String OWNER = "owner";
    protected static final String PAYER = "payer";
    protected static final String FILE = "file";
    protected static final String CONTRACT = "contract";
    protected static final String SCHEDULE = "schedule";
    protected static final String TOPIC = "topic";
    protected static final String TOKEN = "token";
    protected static final int QUERY_COST = 84018;

    /**
     * Creates an account for use as a node operator account, an owner account, and a regular
     * fungible token
     *
     * @return array of operations
     */
    protected static SpecOperation[] createAllAccountsAndTokens() {
        return flattened(
                nodeOperatorAccount(),
                cryptoCreate(OWNER).balance(0L),
                tokenCreate(FUNGIBLE_QUERY_TOKEN)
                        .treasury(OWNER)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(100L));
    }

    protected static SpecOperation[] nodeOperatorAccount() {
        return new SpecOperation[] {
            newKeyNamed(NODE_OPERATOR_KEY),
            cryptoCreate(NODE_OPERATOR).balance(ONE_HUNDRED_HBARS).key(NODE_OPERATOR_KEY),
        };
    }

    protected static SpecOperation payerAccount() {
        return cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS);
    }
}
