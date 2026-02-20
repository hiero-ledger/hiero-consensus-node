// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AutoAssocAsserts.accountTokenPairs;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.contract.Utils.aaWith;
import static com.hedera.services.bdd.suites.contract.Utils.aaWithPreHook;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.hapi.node.base.EvmHookCall;
import com.hedera.hapi.node.base.HookCall;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

// Hooks are enabled by default so it is safe to run this concurrently
// @Tag(SERIAL)
@HapiTestLifecycle
public class HookTimingBalanceOrderTest {
    private static final String OWNER = "owner";
    private static final String RECEIVER = "receiver";
    private static final String SENDER = "sender";
    private static final String FT = "ft";
    private static final long HOOK_ID = 1L;
    private static final String HOOK_CONTRACT_NUM = "365"; // All EVM hooks execute at 0.0.365

    @Contract(contract = "TruePreHook", creationGas = 5000_000L)
    static SpecContract TRUE_PRE_ALLOWANCE_HOOK;

    @Contract(contract = "TransferTokenHook", creationGas = 5000_000L)
    static SpecContract TRANSFER_TOKEN_HOOK;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(TRUE_PRE_ALLOWANCE_HOOK.getInfo());
        testLifecycle.doAdhoc(TRANSFER_TOKEN_HOOK.getInfo());
    }

    @HapiTest
    final Stream<DynamicTest> ownerReceivesBeforeSpendingSucceedsAndRecordValid() {
        return hapiTest(
                cryptoCreate(OWNER).withHook(accountAllowanceHook(HOOK_ID, TRANSFER_TOKEN_HOOK.name())),
                tokenCreate(FT).initialSupply(1_000).treasury(OWNER),
                cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(10),
                cryptoCreate(SENDER).maxAutomaticTokenAssociations(10),
                cryptoTransfer((spec, builder) -> {
                            final var registry = spec.registry();
                            final var tokenAddr = asSolidityAddress(registry.getTokenID(FT));
                            final var toAddr = asSolidityAddress(registry.getAccountID(SENDER));
                            final var amount = 20L;
                            final var encoded = abiEncodeAddressAddressInt64(tokenAddr, toAddr, amount);
                            final var hookCall = HookCall.newBuilder()
                                    .hookId(HOOK_ID)
                                    .evmHookCall(EvmHookCall.newBuilder()
                                            .gasLimit(5000_000L)
                                            .data(Bytes.wrap(encoded)))
                                    .build();
                            builder.setTransfers(TransferList.newBuilder()
                                            .addAccountAmounts(
                                                    aaWithPreHook(registry.getAccountID(OWNER), -20, hookCall))
                                            .addAccountAmounts(aaWith(registry.getAccountID(SENDER), +20))
                                            .build())
                                    .addTokenTransfers(TokenTransferList.newBuilder()
                                            .setToken(registry.getTokenID(FT))
                                            .addAllTransfers(List.of(
                                                    aaWith(registry.getAccountID(SENDER), -10),
                                                    aaWith(registry.getAccountID(RECEIVER), +10)))
                                            .build());
                        })
                        .signedBy(DEFAULT_PAYER, SENDER)
                        .via("preHookReceiveThenSpend"),
                // There should be a successful child record for the hook call
                getTxnRecord("preHookReceiveThenSpend")
                        .andAllChildRecords()
                        .logged()
                        .hasNonStakingChildRecordCount(2)
                        .hasChildRecords(
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith().contract(HOOK_CONTRACT_NUM)),
                                recordWith()
                                        .status(SUCCESS)
                                        .autoAssociated(accountTokenPairs(List.of(Pair.of(SENDER, FT))))));
    }

    @HapiTest
    final Stream<DynamicTest> ownerSpendsBeforeReceivingFailsAtBalanceCheck() {
        return hapiTest(
                // Register a hook that does NOT fund in pre; any funding would come too late (post)
                cryptoCreate(OWNER).withHook(accountAllowanceHook(HOOK_ID, TRANSFER_TOKEN_HOOK.name())),
                cryptoCreate(SENDER).maxAutomaticTokenAssociations(10),
                cryptoCreate(RECEIVER).maxAutomaticTokenAssociations(10),
                // Create the token with SENDER as treasury so OWNER starts with 0 balance
                tokenCreate(FT).initialSupply(1_000).treasury(SENDER),
                // Ensure OWNER and RECEIVER are associated to the token
                tokenAssociate(OWNER, FT),
                tokenAssociate(RECEIVER, FT),

                // OWNER tries to spend FT before receiving any; pre-hook does not fund -> should fail at balance check
                cryptoTransfer((spec, builder) -> {
                            final var registry = spec.registry();
                            final var hookCall = HookCall.newBuilder()
                                    .hookId(HOOK_ID)
                                    .evmHookCall(EvmHookCall.newBuilder().gasLimit(5000_000L))
                                    .build();
                            builder.addTokenTransfers(TokenTransferList.newBuilder()
                                    .setToken(registry.getTokenID(FT))
                                    .addAllTransfers(List.of(
                                            aaWithPreHook(registry.getAccountID(OWNER), -10, hookCall),
                                            aaWith(registry.getAccountID(RECEIVER), +10)))
                                    .build());
                        })
                        .signedBy(DEFAULT_PAYER, OWNER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK)
                        .via("spendBeforeReceiveFails"),
                // Log the record and any children for debugging; no child record assertions since parent fails early
                getTxnRecord("spendBeforeReceiveFails")
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(CONTRACT_REVERT_EXECUTED))
                        .logged());
    }

    private static byte[] abiEncodeAddressAddressInt64(byte[] addr1, byte[] addr2, long amount) {
        final byte[] out = new byte[96];
        // address addr1 in first 32-byte word (left-padded)
        System.arraycopy(addr1, 0, out, 12, 20);
        // address addr2 in second 32-byte word (left-padded)
        System.arraycopy(addr2, 0, out, 32 + 12, 20);
        // int64 amount in third 32-byte word (left-padded)
        final byte[] amt = ByteBuffer.allocate(8).putLong(amount).array();
        System.arraycopy(amt, 0, out, 64 + 24, 8);
        return out;
    }
}
