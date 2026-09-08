// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.NODE_STAKING;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createDefaultContract;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_STAKING_ID;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Crypto/contract tests that stake an account or contract to a consensus node. Tagged
 * {@code NODE_STAKING} so they run on a {@code writerMode=FILE} task off the block node, which
 * cannot verify block proofs across the roster handoff a node-weight change forces at a
 * stake-period boundary.
 */
@Tag(NODE_STAKING)
public class CryptoStakingSuite {
    private static final long ACCOUNT_ID = 10;
    private static final String ADMIN_KEY = "adminKey";

    @HapiTest
    final Stream<DynamicTest> createAnAccountWithStakingFields() {
        return hapiTest(
                cryptoCreate("civilianWORewardStakingNode")
                        .balance(ONE_HUNDRED_HBARS)
                        .declinedReward(true)
                        .stakedNodeId(0),
                getAccountInfo("civilianWORewardStakingNode")
                        .has(accountWith()
                                .isDeclinedReward(true)
                                .noStakedAccountId()
                                .stakedNodeId(0)),
                cryptoCreate("civilianWORewardStakingAcc")
                        .balance(ONE_HUNDRED_HBARS)
                        .declinedReward(true)
                        .stakedAccountId(ACCOUNT_ID),
                getAccountInfo("civilianWORewardStakingAcc")
                        .has(accountWith()
                                .isDeclinedReward(true)
                                .noStakingNodeId()
                                .stakedAccountId(ACCOUNT_ID)),
                cryptoCreate("civilianWRewardStakingNode")
                        .balance(ONE_HUNDRED_HBARS)
                        .declinedReward(false)
                        .stakedNodeId(0),
                getAccountInfo("civilianWRewardStakingNode")
                        .has(accountWith()
                                .isDeclinedReward(false)
                                .noStakedAccountId()
                                .stakedNodeId(0)),
                cryptoCreate("civilianWRewardStakingAcc")
                        .balance(ONE_HUNDRED_HBARS)
                        .declinedReward(false)
                        .stakedAccountId(ACCOUNT_ID),
                getAccountInfo("civilianWRewardStakingAcc")
                        .has(accountWith()
                                .isDeclinedReward(false)
                                .noStakingNodeId()
                                .stakedAccountId(ACCOUNT_ID)),
                /* --- sentinel values throw */
                cryptoCreate("invalidStakedAccount")
                        .balance(ONE_HUNDRED_HBARS)
                        .declinedReward(false)
                        .stakedAccountId("0")
                        .hasPrecheck(INVALID_STAKING_ID),
                cryptoCreate("invalidStakedNode")
                        .balance(ONE_HUNDRED_HBARS)
                        .declinedReward(false)
                        .stakedNodeId(-1L)
                        .hasPrecheck(INVALID_STAKING_ID));
    }

    @HapiTest
    final Stream<DynamicTest> updateStakingFieldsWorks() {
        final var stakedAccountId = 20;
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate("user").key(ADMIN_KEY).stakedAccountId(20).declinedReward(true),
                getAccountInfo("user")
                        .has(accountWith()
                                .stakedAccountId(stakedAccountId)
                                .noStakingNodeId()
                                .isDeclinedReward(true)),
                cryptoUpdate("user").newStakedNodeId(0L).newDeclinedReward(false),
                getAccountInfo("user")
                        .has(accountWith().noStakedAccountId().stakedNodeId(0L).isDeclinedReward(false)),
                cryptoUpdate("user").newStakedNodeId(-1L),
                cryptoUpdate("user").newStakedNodeId(-25L).hasKnownStatus(INVALID_STAKING_ID),
                getAccountInfo("user")
                        .has(accountWith().noStakedAccountId().noStakingNodeId().isDeclinedReward(false)),
                cryptoUpdate("user").key(ADMIN_KEY).newStakedAccountId("20").newDeclinedReward(true),
                getAccountInfo("user")
                        .has(accountWith()
                                .stakedAccountId(stakedAccountId)
                                .noStakingNodeId()
                                .isDeclinedReward(true))
                        .logged(),
                cryptoUpdate("user").key(ADMIN_KEY).newStakedAccountId("0.0.0"),
                getAccountInfo("user")
                        .has(accountWith().noStakedAccountId().noStakingNodeId().isDeclinedReward(true))
                        .logged(),
                // For completeness stake back to a node
                cryptoUpdate("user").key(ADMIN_KEY).newStakedNodeId(1),
                getAccountInfo("user").has(accountWith().stakedNodeId(1L).isDeclinedReward(true)));
    }

    @HapiTest
    final Stream<DynamicTest> noStakePeriodStartIfNotStakingToNode() {
        final var user = "user";
        final var contract = "contract";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(user).key(ADMIN_KEY).stakedNodeId(0L),
                createDefaultContract(contract).adminKey(ADMIN_KEY).stakedNodeId(0L),
                getAccountInfo(user).has(accountWith().someStakePeriodStart()),
                getContractInfo(contract).has(contractWith().someStakePeriodStart()),
                cryptoUpdate(user).newStakedAccountId(contract),
                contractUpdate(contract).newStakedAccountId(user),
                getAccountInfo(user).has(accountWith().noStakePeriodStart()),
                getContractInfo(contract).has(contractWith().noStakePeriodStart()));
    }
}
