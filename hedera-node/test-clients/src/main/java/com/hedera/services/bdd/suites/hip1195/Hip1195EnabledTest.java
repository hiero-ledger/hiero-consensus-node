// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.lambdaAccountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewAccount;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewContract;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.contract.Utils.extractBytecodeUnhexed;
import static com.hedera.services.bdd.suites.contract.Utils.getResourcePath;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_ID_IN_USE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_ID_REPEATED_IN_CREATION_DETAILS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.state.token.Account;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hederahashgraph.api.proto.java.ContractID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class Hip1195EnabledTest {
    @Contract(contract = "PayableConstructor")
    static SpecContract HOOK_CONTRACT;

    @Contract(contract = "SmartContractsFees")
    static SpecContract HOOK_UPDATE_CONTRACT;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(HOOK_CONTRACT.getInfo());
        testLifecycle.doAdhoc(HOOK_UPDATE_CONTRACT.getInfo());
    }

    @HapiTest
    final Stream<DynamicTest> createAndUpdateAccountWithHooks() {
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate("testAccount")
                        .key("adminKey")
                        .balance(1L)
                        .withHook(lambdaAccountAllowanceHook(123L, HOOK_CONTRACT.name()))
                        .withHook(lambdaAccountAllowanceHook(124L, HOOK_CONTRACT.name()))
                        .withHook(lambdaAccountAllowanceHook(125L, HOOK_CONTRACT.name())),
                viewAccount("testAccount", account -> {
                    assertEquals(123L, account.firstHookId());
                    assertEquals(3, account.numberHooksInUse());
                }),
                cryptoUpdate("testAccount")
                        .withHook(lambdaAccountAllowanceHook(127L, HOOK_CONTRACT.name()))
                        .withHook(lambdaAccountAllowanceHook(128L, HOOK_CONTRACT.name()))
                        .withHook(lambdaAccountAllowanceHook(129L, HOOK_CONTRACT.name())),
                viewAccount("testAccount", account -> {
                    assertEquals(127L, account.firstHookId());
                    assertEquals(6, account.numberHooksInUse());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> duplicateHookIdsInOneList_failsPrecheck() {
        final var OWNER = "acctDupIds";
        final var H1 = lambdaAccountAllowanceHook(7L, HOOK_CONTRACT.name());
        final var H2 = lambdaAccountAllowanceHook(7L, HOOK_CONTRACT.name());

        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER)
                        .key("k")
                        .balance(1L)
                        .withHook(H1)
                        .withHook(H2)
                        .hasPrecheck(HOOK_ID_REPEATED_IN_CREATION_DETAILS));
    }

    @HapiTest
    final Stream<DynamicTest> deleteHooksAndLinkNewOnes() {
        final var OWNER = "acctHeadRun";
        final long A = 1L, B = 2L, C = 3L, D = 4L, E = 5L;

        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER)
                        .key("k")
                        .balance(1L)
                        .withHooks(
                                lambdaAccountAllowanceHook(A, HOOK_CONTRACT.name()),
                                lambdaAccountAllowanceHook(B, HOOK_CONTRACT.name()),
                                lambdaAccountAllowanceHook(C, HOOK_CONTRACT.name())),
                cryptoUpdate(OWNER)
                        .withHooks(
                                lambdaAccountAllowanceHook(A, HOOK_CONTRACT.name()),
                                lambdaAccountAllowanceHook(E, HOOK_CONTRACT.name()))
                        .hasKnownStatus(HOOK_ID_IN_USE),
                // Delete A,B (at head) and add D,E. Head should become D (the first in the creation list)
                cryptoUpdate(OWNER)
                        .removingHooks(A, B)
                        .withHooks(
                                lambdaAccountAllowanceHook(D, HOOK_CONTRACT.name()),
                                lambdaAccountAllowanceHook(E, HOOK_CONTRACT.name())),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(D, a.firstHookId());
                    // started with 3; minus 2 deletes; plus 2 adds -> 3 again
                    assertEquals(3, a.numberHooksInUse());
                }),
                cryptoUpdate(OWNER)
                        .removingHooks(A)
                        .withHooks(lambdaAccountAllowanceHook(A, HOOK_CONTRACT.name()))
                        .hasKnownStatus(HOOK_NOT_FOUND),
                cryptoUpdate(OWNER).removingHooks(D).withHooks(lambdaAccountAllowanceHook(D, HOOK_CONTRACT.name())));
    }

    @HapiTest
    final Stream<DynamicTest> contractCreateWithHooks() {
        final var OWNER = "contractOwner";
        final AtomicReference<ContractID> contractId = new AtomicReference<>();
        return hapiTest(
                contractCreate(OWNER)
                        .inlineInitCode(extractBytecodeUnhexed(getResourcePath("CreateTrivial", ".bin")))
                        .exposingContractIdTo(contractId::set)
                        .balance(0)
                        .withHooks(
                                lambdaAccountAllowanceHook(21L, HOOK_CONTRACT.name()),
                                lambdaAccountAllowanceHook(22L, HOOK_CONTRACT.name())),
                viewContract(OWNER, (Account c) -> {
                    assertEquals(21L, c.firstHookId(), "firstHookId should be the first id in the list");
                    assertEquals(2, c.numberHooksInUse(), "contract account should track hook count");
                }),
                contractUpdate(OWNER)
                        .withHooks(
                                lambdaAccountAllowanceHook(21L, HOOK_UPDATE_CONTRACT.name()),
                                lambdaAccountAllowanceHook(23L, HOOK_CONTRACT.name()))
                        .hasKnownStatus(HOOK_ID_IN_USE),
                contractUpdate(OWNER)
                        .removingHook(21L)
                        .withHooks(
                                lambdaAccountAllowanceHook(23L, HOOK_UPDATE_CONTRACT.name()),
                                lambdaAccountAllowanceHook(21L, HOOK_CONTRACT.name())),
                viewContract(OWNER, (Account c) -> {
                    assertEquals(23L, c.firstHookId());
                    assertEquals(3, c.numberHooksInUse());
                }));
    }
}
