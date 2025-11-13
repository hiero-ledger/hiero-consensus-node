// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import static com.hedera.hapi.node.hooks.HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountLambdaSStore;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THOUSAND_HBAR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.Hook;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.utils.InitcodeTransform;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.stream.Stream;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class LambdaplexTest implements InitcodeTransform {
    private static final int HOOK_ID = 42;

    private static final String REGISTRY_ADDRESS_TPL = "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcd";

    private enum Side {
        BUY,
        SELL
    }

    private enum Type {
        MARKET,
        LIMIT,
        STOP_MARKET,
        STOP_LIMIT
    }

    @Contract(contract = "MockSupraRegistry", creationGas = 1_000_000L)
    static SpecContract MOCK_SUPRA_REGISTRY;

    @Contract(contract = "LambdaplexHook", creationGas = 2_000_000L, initcodeTransform = LambdaplexTest.class)
    static SpecContract LAMBDAPLEX_HOOK;

    @FungibleToken(initialSupply = 10_000 * 10_000L, decimals = 4)
    static SpecFungibleToken APPLES;

    @FungibleToken(initialSupply = 10_000 * 100_000L, decimals = 5)
    static SpecFungibleToken BANANAS;

    @FungibleToken(initialSupply = 10_000 * 1_000_000L, decimals = 6)
    static SpecFungibleToken USDC;

    @Account(
            name = "marketMaker",
            tinybarBalance = THOUSAND_HBAR,
            maxAutoAssociations = 3,
            hooks = {@Hook(hookId = HOOK_ID, contract = "LambdaplexHook", extensionPoint = ACCOUNT_ALLOWANCE_HOOK)})
    static SpecAccount MARKET_MAKER;

    @Account(
            name = "party",
            tinybarBalance = ONE_HUNDRED_HBARS,
            maxAutoAssociations = 3,
            hooks = {@Hook(hookId = HOOK_ID, contract = "LambdaplexHook", extensionPoint = ACCOUNT_ALLOWANCE_HOOK)})
    static SpecAccount PARTY;

    @Account(
            name = "counterparty",
            tinybarBalance = ONE_HUNDRED_HBARS,
            maxAutoAssociations = 3,
            hooks = {@Hook(hookId = HOOK_ID, contract = "LambdaplexHook", extensionPoint = ACCOUNT_ALLOWANCE_HOOK)})
    static SpecAccount COUNTERPARTY;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(
                MOCK_SUPRA_REGISTRY.getInfo(),
                LAMBDAPLEX_HOOK.getInfo(),
                APPLES.getInfo(),
                BANANAS.getInfo(),
                USDC.getInfo(),
                MOCK_SUPRA_REGISTRY.call("registerPair", BigInteger.ONE, APPLES, USDC),
                MOCK_SUPRA_REGISTRY.call("registerPair", BigInteger.TWO, BANANAS, USDC),
                MARKET_MAKER.getInfo(),
                cryptoTransfer(
                        moving(1000, APPLES.name()).between(APPLES.treasury().name(), MARKET_MAKER.name()),
                        moving(1000, BANANAS.name()).between(BANANAS.treasury().name(), MARKET_MAKER.name()),
                        moving(1000, USDC.name()).between(USDC.treasury().name(), MARKET_MAKER.name())),
                cryptoTransfer(
                        moving(100, APPLES.name()).between(APPLES.treasury().name(), PARTY.name()),
                        moving(100, BANANAS.name()).between(BANANAS.treasury().name(), PARTY.name()),
                        moving(100, USDC.name()).between(USDC.treasury().name(), PARTY.name())),
                cryptoTransfer(
                        moving(100, APPLES.name()).between(APPLES.treasury().name(), COUNTERPARTY.name()),
                        moving(100, BANANAS.name()).between(BANANAS.treasury().name(), COUNTERPARTY.name()),
                        moving(100, USDC.name()).between(USDC.treasury().name(), COUNTERPARTY.name())));
    }

    @HapiTest
    final Stream<DynamicTest> isolatedExecutionWithNonHookStorageSideEffectsPassesParity() {
        return hapiTest(
                revertingNextOraclePull(),
                MOCK_SUPRA_REGISTRY
                        .call("verifyOracleProofV2", new Object[] {new byte[0]})
                        .andAssert(op -> op.hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                LAMBDAPLEX_HOOK
                        .staticCall("supra")
                        .andAssert(op -> op.exposingRawResultsTo(bytes -> System.out.println(CommonUtils.hex(bytes)))));
    }

    private SpecOperation revertingNextOraclePull() {
        return MOCK_SUPRA_REGISTRY.call("mockNextRevert");
    }

    private SpecOperation placeLimitOrder(
            @NonNull final SpecAccount account,
            @NonNull final SpecFungibleToken specBaseToken,
            @NonNull final SpecFungibleToken specQuoteToken,
            @NonNull final Side side,
            @NonNull final BigDecimal price,
            @NonNull final BigDecimal quantity) {
        return sourcingContextual(spec -> {
            final var targetNetwork = spec.targetNetworkOrThrow();
            final var baseToken = specBaseToken.tokenOrThrow(targetNetwork);
            final var quoteToken = specQuoteToken.tokenOrThrow(targetNetwork);
            return accountLambdaSStore(account.name(), HOOK_ID)
                    .putMappingEntryWithKey(Bytes.EMPTY, encodeOrderKey(), encodeOrderDetail());
        });
    }

    private Bytes encodeOrderKey() {
        return Bytes.EMPTY;
    }

    private Bytes encodeOrderDetail() {
        return Bytes.EMPTY;
    }

    // --- InitcodeTransform ---
    @Override
    public String transformHexed(@NonNull final HapiSpec spec, @NonNull final String initcode) {
        var registryAddress = asLongZeroAddress(spec.registry()
                        .getContractId(MOCK_SUPRA_REGISTRY.name())
                        .getContractNum())
                .toHexString()
                .toLowerCase();
        if (registryAddress.startsWith("0x")) {
            registryAddress = registryAddress.substring(2);
        }
        return initcode.replace(REGISTRY_ADDRESS_TPL, registryAddress);
    }
}
