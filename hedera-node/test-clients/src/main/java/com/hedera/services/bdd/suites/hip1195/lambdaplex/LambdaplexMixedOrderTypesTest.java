// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195.lambdaplex;

import static com.hedera.hapi.node.hooks.HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THOUSAND_HBAR;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.Hook;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.utils.InitcodeTransform;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

/**
 * Tests exercising the Lambdaplex protocol hook, with an emphasis on stop orders. (Has some intentional mild
 * duplication with other test classes in this package to keep each test class more self-contained.)
 * <p>
 * The entries in mapping zero of this hook represent orders (limit, market, stop limit, or stop market) on a
 * self-custodied spot exchange. Each mapping's key and value are packed bytes with a specific layout.
 * <p>
 * The mapping key's bytes include,
 * <ul>
 *   <li>The <b>order type</b> (limit, market, stop limit, or stop market); and for a stop, the <b>direction</b>.</li>
 *   <li>The Hedera token entity number of the <b>output token</b> the hook owner is willing to be debited.</li>
 *   <li>The Hedera token entity number of the <b>input token</b> the hook owner wants to be credited.</li>
 *   <li>The maximum fee, in <b>centi-bps (hundredths of a basis point)</b>, the hook owner is willing to pay. (This
 *   fee is always deducted from the input token amount.)</li>
 *   <li>The consensus expiration second at which the order expires.</li>
 *   <li>A 7-byte <b>salt</b> that is globally unique across all the user's orders; might be a counter or random.</li>
 * </ul>
 * And the mapping value's bytes include,
 * <ul>
 *   <li>The order size as a <b>maximum debit</b> in base units of the output token.</li>
 *   <li>The order's <b>price</b>, in base units of the input token per base unit of the output token. For a limit or
 *   stop limit order, this is exact price at which the order must fill. For a market or stop market order, this is the
 *   reference price to compute slippage tolerance against. (So it is also the trigger price for a stop market order.)
 *   </li>
 *   <li>The order's <b>maximum deviation</b> from its price, in <b>centi-bps</b>. This has no
 *   significance for a limit order, but for a stop limit order implies the trigger price. For a market or stop
 *   market order, this is the slippage tolerance.</li>
 *   <li>The order's <b>minimum fill</b> fraction in centi-bps; hence if set to one million, the order has onchain
 *   fill-or-kill semantics.</li>
 * </ul>
 * Stop orders are triggered by providing an oracle proof of a (sufficiently recent) price that hits the trigger.
 * <p>
 * Annotated as {@link OrderedInIsolation} because the mock Supra pull oracle is stateful (keeps the next expected
 * {@code PriceInfo} in storage).
 */
@HapiTestLifecycle
@OrderedInIsolation
@Tag(MATS)
public class LambdaplexMixedOrderTypesTest implements InitcodeTransform {
    private static final int HOOK_ID = 42;
    private static final int ZERO_BPS = 0;
    private static final int MAKER_BPS = 12;
    private static final int TAKER_BPS = 25;
    private static final Fees FEES = new Fees(MAKER_BPS, TAKER_BPS);

    private static final String REGISTRY_ADDRESS_TPL = "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcd";

    private static final int APPLES_DECIMALS = 4;
    private static final int BANANAS_DECIMALS = 5;
    private static final int USDC_DECIMALS = 6;
    private static final long APPLES_SCALE = 10L * 10L * 10L * 10L;
    private static final long BANANAS_SCALE = 10L * 10L * 10L * 10L * 10L;
    private static final long USDC_SCALE = 10L * 10L * 10L * 10L * 10L * 10L;

    @Contract(contract = "MockSupraRegistry", creationGas = 1_000_000L)
    static SpecContract MOCK_SUPRA_REGISTRY;

    @Contract(
            contract = "OrderFlowAllowance",
            creationGas = 2_000_000L,
            initcodeTransform = LambdaplexMixedOrderTypesTest.class)
    static SpecContract LAMBDAPLEX_HOOK;

    @FungibleToken(initialSupply = 10_000 * APPLES_SCALE, decimals = APPLES_DECIMALS)
    static SpecFungibleToken APPLES;

    @FungibleToken(initialSupply = 10_000 * BANANAS_SCALE, decimals = BANANAS_DECIMALS)
    static SpecFungibleToken BANANAS;

    @FungibleToken(initialSupply = 10_000 * USDC_SCALE, decimals = USDC_DECIMALS)
    static SpecFungibleToken USDC;

    @Account(
            name = "marketMaker",
            tinybarBalance = THOUSAND_HBAR,
            maxAutoAssociations = 3,
            hooks = {@Hook(hookId = HOOK_ID, contract = "OrderFlowAllowance", extensionPoint = ACCOUNT_ALLOWANCE_HOOK)})
    static SpecAccount MARKET_MAKER;

    @Account(
            name = "party",
            tinybarBalance = ONE_HUNDRED_HBARS,
            maxAutoAssociations = 3,
            hooks = {@Hook(hookId = HOOK_ID, contract = "OrderFlowAllowance", extensionPoint = ACCOUNT_ALLOWANCE_HOOK)})
    static SpecAccount PARTY;

    @Account(
            name = "counterparty",
            tinybarBalance = ONE_HUNDRED_HBARS,
            maxAutoAssociations = 3,
            hooks = {@Hook(hookId = HOOK_ID, contract = "OrderFlowAllowance", extensionPoint = ACCOUNT_ALLOWANCE_HOOK)})
    static SpecAccount COUNTERPARTY;

    @Account(name = "feeCollector", maxAutoAssociations = 3)
    static SpecAccount FEE_COLLECTOR;

    private final LambdaplexVerbs lv = new LambdaplexVerbs(HOOK_ID, "feeCollector");

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
                PARTY.getInfo(),
                COUNTERPARTY.getInfo(),
                FEE_COLLECTOR.getInfo(),
                // Initialize everyone's token balances
                cryptoTransfer(
                        moving(applesUnits(1000), APPLES.name())
                                .between(APPLES.treasury().name(), MARKET_MAKER.name()),
                        moving(bananasUnits(1000), BANANAS.name())
                                .between(BANANAS.treasury().name(), MARKET_MAKER.name()),
                        moving(usdcUnits(1000), USDC.name())
                                .between(USDC.treasury().name(), MARKET_MAKER.name())),
                cryptoTransfer(
                        moving(applesUnits(100), APPLES.name())
                                .between(APPLES.treasury().name(), PARTY.name()),
                        moving(bananasUnits(100), BANANAS.name())
                                .between(BANANAS.treasury().name(), PARTY.name()),
                        moving(usdcUnits(100), USDC.name())
                                .between(USDC.treasury().name(), PARTY.name())),
                cryptoTransfer(
                        moving(applesUnits(100), APPLES.name())
                                .between(APPLES.treasury().name(), COUNTERPARTY.name()),
                        moving(bananasUnits(100), BANANAS.name())
                                .between(BANANAS.treasury().name(), COUNTERPARTY.name()),
                        moving(usdcUnits(100), USDC.name())
                                .between(USDC.treasury().name(), COUNTERPARTY.name())));
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

    private static long applesUnits(long apples) {
        return apples * APPLES_SCALE;
    }

    private static long bananasUnits(long bananas) {
        return bananas * BANANAS_SCALE;
    }

    private static long usdcUnits(long usdc) {
        return usdc * USDC_SCALE;
    }
}
