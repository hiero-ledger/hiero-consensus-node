// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip904;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.dsl.operations.transactions.AirdropOperation.Airdrop.forFungible;
import static com.hedera.services.bdd.spec.dsl.operations.transactions.AirdropOperation.Airdrop.forNonFungibleToken;
import static com.hedera.services.bdd.spec.dsl.operations.transactions.TouchBalancesOperation.touchBalanceOf;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHollow;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.OwningEntity;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.dsl.operations.transactions.AirdropOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.AirdropOperation.Airdrop.AliasUsage;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

/**
 * Characterizes the interactions of HIP-904 transactions and signing requirements with,
 * <ol>
 *     <li>Contract accounts, with and without cryptographic admin keys.</li>
 *     <li>Hollow accounts.</li>
 *     <li>Aliased account ids.</li>
 *     <li>Accounts with {@link com.hedera.hapi.node.state.token.Account#receiverSigRequired()} true.</li>
 * </ol>
 */
@DisplayName("airdrops")
@HapiTestLifecycle
public class AirdropSigReqsTest {
    private static final AtomicLong nextSerialNoToDrop = new AtomicLong(1);

    @Account(tinybarBalance = 10 * ONE_HUNDRED_HBARS)
    static SpecAccount airdropOrigin;

    @FungibleToken
    static SpecFungibleToken fungibleToken;

    @NonFungibleToken(numPreMints = 20)
    static SpecNonFungibleToken nonFungibleToken;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        fungibleToken.setTreasury(airdropOrigin);
        nonFungibleToken.setTreasury(airdropOrigin);
        lifecycle.overrideInClass(Map.of(
                "tokens.airdrops.enabled", "true",
                "entities.unlimitedAutoAssociationsEnabled", "true"));
    }

    @HapiTest
    @DisplayName("always work for a receiver hollow account")
    final Stream<DynamicTest> alwaysWorkForHollowAccount() {
        return hapiTest(
                // Ensure these entities exist since hollow accounts are not yet supported by the OO DSL
                touchBalanceOf(airdropOrigin),
                fungibleToken.getInfo(),
                createHollow(1, i -> "hollow" + i),
                tokenAirdrop(moving(1, fungibleToken.name()).between(airdropOrigin.name(), "hollow0"))
                        .payingWith(airdropOrigin.name())
                        .via("airdrop"),
                getTxnRecord("airdrop").hasPriority(recordWith().pendingAirdropsCount(0)),
                getAccountBalance("hollow0").hasTokenBalance(fungibleToken.name(), 1));
    }

    @HapiTest
    @DisplayName("complete a sender hollow account")
    final Stream<DynamicTest> completeSenderHollowAccount(@Account SpecAccount misc) {
        final var serialNo = nextSerialNoToDrop.getAndIncrement();
        return hapiTest(
                // Ensure these entities exist since hollow accounts are not yet supported by the OO DSL
                touchBalanceOf(airdropOrigin, misc),
                nonFungibleToken.getInfo(),
                createHollow(
                        1,
                        i -> "hollow" + i,
                        address -> cryptoTransfer(movingUnique(nonFungibleToken.name(), serialNo)
                                .between(airdropOrigin.name(), address))),
                getAccountInfo("hollow0").isHollow(),
                tokenAirdrop(movingUnique(nonFungibleToken.name(), serialNo).between("hollow0", misc.name()))
                        .signedBy(DEFAULT_PAYER, "forHollow0")
                        .sigMapPrefixes(uniqueWithFullPrefixesFor("forHollow0"))
                        .via("airdrop"),
                getTxnRecord("airdrop")
                        .andAllChildRecords()
                        .hasPriority(recordWith().pendingAirdropsCount(1))
                        .hasNonStakingChildRecordCount(1),
                getAccountInfo("hollow0").isNotHollow());
    }

    @HapiTest
    @DisplayName("work with all combinations of sender/receiver aliases")
    final Stream<DynamicTest> workWithAllAliasCombinations(@Account SpecAccount receiver) {
        return hapiTest(
                airdropTo(receiver, AliasUsage.NEITHER),
                airdropTo(receiver, AliasUsage.RECEIVER),
                airdropTo(receiver, AliasUsage.SENDER),
                airdropTo(receiver, AliasUsage.BOTH));
    }

    private AirdropOperation airdropTo(final OwningEntity receiver) {
        return airdropTo(receiver, AliasUsage.NEITHER);
    }

    private AirdropOperation airdropTo(@NonNull final OwningEntity receiver, @NonNull final AliasUsage aliasUsage) {
        return airdropOrigin.doAirdrops(
                forFungible(fungibleToken, receiver, 1L, aliasUsage),
                forNonFungibleToken(nonFungibleToken, receiver, nextSerialNoToDrop.getAndIncrement(), aliasUsage));
    }
}
