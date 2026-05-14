package com.hedera.services.bdd.suites.contract.ethereum;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class HeliswapTest {

    private static final int SWAPPING_ACCOUNTS_NUMBER = 10;
    private static final int SWAPS_NUMBER = 10;

    private static final String OWNER_ACCOUNT = "HeliswapOwner";
    private static final String SWAP_ACCOUNT = "SwapAccount";
    private static final String USDC_CONTRACT = "HeliswapUSDC";
    private static final String WHBAR_CONTRACT = "HeliswapWHBAR";
    private static final String FACTORY_CONTRACT = "HeliswapUniswapV2Factory";
    private static final String ROUTER_CONTRACT = "HeliswapUniswapV2Router";
    private static final BigInteger USDC_LIQUIDITY = BigInteger.valueOf(10000000000L);

    static final AtomicReference<Address> ownerAccountAddress = new AtomicReference<>();
    static final AtomicReference<Address> usdcContractAddress = new AtomicReference<>();
    static final AtomicReference<Address> whbarContractAddress = new AtomicReference<>();
    static final AtomicReference<Address> factoryContractAddress = new AtomicReference<>();
    static final AtomicReference<Address> routerContractAddress = new AtomicReference<>();
    static final AtomicReference<Address> pairContractAddress = new AtomicReference<>();
    static final List<Address> swapAccountsAddresses = new ArrayList<>();

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                // account create
                cryptoCreate(OWNER_ACCOUNT).balance(ONE_MILLION_HBARS)
                        .exposingEvmAddressTo(ownerAccountAddress::set),
                // contracts create
                uploadInitCode(USDC_CONTRACT),
                uploadInitCode(WHBAR_CONTRACT),
                uploadInitCode(FACTORY_CONTRACT),
                uploadInitCode(ROUTER_CONTRACT),
                sourcing(() -> contractCreate(USDC_CONTRACT, ownerAccountAddress.get())
                        .payingWith(OWNER_ACCOUNT)
                        .gas(12_000_000)
                        .exposingAddressTo(usdcContractAddress::set)),
                sourcing(() -> contractCreate(WHBAR_CONTRACT)
                        .payingWith(OWNER_ACCOUNT)
                        .gas(12_000_000)
                        .exposingAddressTo(whbarContractAddress::set)),
                sourcing(() -> contractCreate(FACTORY_CONTRACT, ownerAccountAddress.get())
                        .payingWith(OWNER_ACCOUNT)
                        .gas(12_000_000)
                        .exposingAddressTo(factoryContractAddress::set)),
                sourcing(() -> contractCreate(ROUTER_CONTRACT, factoryContractAddress.get(), whbarContractAddress.get())
                        .payingWith(OWNER_ACCOUNT)
                        .gas(12_000_000)
                        .exposingAddressTo(routerContractAddress::set)),
                // create pair
                sourcing(() -> contractCall(FACTORY_CONTRACT, "createPair", usdcContractAddress.get(), whbarContractAddress.get())
                        .payingWith(OWNER_ACCOUNT)
                        .gas(12_000_000)
                        .exposingResultTo(e -> pairContractAddress.set((Address) e[0]))),
                // provide liquidity
                sourcing(() -> contractCall(USDC_CONTRACT, "approve", routerContractAddress.get(), USDC_LIQUIDITY)
                        .payingWith(OWNER_ACCOUNT)
                        .gas(500_000)),
                sourcing(() -> contractCall(USDC_CONTRACT, "allowance", ownerAccountAddress.get(), routerContractAddress.get())
                        .payingWith(OWNER_ACCOUNT)
                        .gas(32_000)),
                sourcing(() -> contractCall(ROUTER_CONTRACT, "addLiquidityHBAR", usdcContractAddress.get(), USDC_LIQUIDITY, USDC_LIQUIDITY, BigInteger.valueOf(100L), ownerAccountAddress.get(), BigInteger.valueOf(System.currentTimeMillis() / 1000 + 3600 * 100))
                        .payingWith(OWNER_ACCOUNT)
                        .sending(ONE_HUNDRED_HBARS)
                        .gas(1_000_000)
                        .exposingResultTo(e -> System.out.printf("--------->Add Liquidity HBAR: USDC: %s HBar: %s liquidity: %s%n", e[0], e[1], e[2])))
        );
        // create swapping accounts
        lifecycle.doAdhoc(
                IntStream.range(0, SWAPPING_ACCOUNTS_NUMBER)
                        .mapToObj(i ->
                                cryptoCreate(SWAP_ACCOUNT + "_" + i).balance(ONE_HUNDRED_HBARS)
                                        .exposingEvmAddressTo(swapAccountsAddresses::add)
                        )
                        .toArray(SpecOperation[]::new)
        );
    }

    @HapiTest
    final Stream<DynamicTest> heliswapTest() {
        return hapiTest(
                IntStream.range(0, SWAPS_NUMBER).boxed().flatMap(swapIndex ->
                                IntStream.range(0, SWAPPING_ACCOUNTS_NUMBER).mapToObj(accountIndex ->
                                        contractCall(ROUTER_CONTRACT, "swapExactHBARForTokens",
                                                BigInteger.valueOf(1), new Address[]{whbarContractAddress.get(), usdcContractAddress.get()}, swapAccountsAddresses.get(accountIndex), BigInteger.valueOf(System.currentTimeMillis() / 1000 + 180))
                                                .payingWith(OWNER_ACCOUNT)
                                                .gas(250_000L)
                                                .sending(2 * ONE_HBAR)
                                                .exposingResultTo(e -> System.out.printf("--------->Swap[%s/%s] %s%n", accountIndex, swapIndex, Arrays.toString((BigInteger[]) e[0])))
                                )
                        )
                        .toArray(SpecOperation[]::new)
        );
    }
}
