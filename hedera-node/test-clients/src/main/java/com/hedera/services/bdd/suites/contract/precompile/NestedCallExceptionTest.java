// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getNestedContractAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.TokenType;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Exercises a failing HTS (system-contract) call nested one contract level deep, across the three
 * EVM call operations: a regular {@code CALL}, a {@code DELEGATECALL}, and a {@code STATICCALL}.
 *
 * <p>The failure is triggered via a business-failure revert (the innermost HTS mint is not
 * authorized, or cannot mutate state in a static frame), so the outer contract's {@code revert(...)}
 * fires and the whole transaction resolves to {@link
 * com.hederahashgraph.api.proto.java.ResponseCodeEnum#CONTRACT_REVERT_EXECUTED}.
 *
 * <p>Each test asserts the top-level revert status and that the failed nested call left token supply
 * unchanged. Asserting on the contract-action sidecars / gas fields of the individual nested frames
 * (via {@code SidecarVerbs}) is a natural follow-up once these run green.
 *
 * <p>Contracts reused (already present under {@code src/main/resources/contract/contracts}):
 * <ul>
 *   <li>Regular CALL: {@code NestedBurn} -&gt; {@code MintToken} -&gt; HTS mint.</li>
 *   <li>DELEGATECALL: {@code DelegateContract} -&gt; {@code ServiceContract} -&gt; HTS mint.</li>
 *   <li>STATICCALL: {@code StaticContract} -&gt; {@code ServiceContract} -&gt; HTS mint.</li>
 * </ul>
 */
@Tag(SMART_CONTRACT)
public class NestedCallExceptionTest {
    private static final long GAS_TO_OFFER = 4_000_000L;
    private static final String TOKEN_TREASURY = "treasury";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String SUPPLY_KEY = "supplyKey";

    // Regular CALL nesting: SIGNER -> call -> NestedBurn -> call -> MintToken -> HTS
    private static final String NESTED_BURN = "NestedBurn";
    private static final String MINT_TOKEN = "MintToken";
    // DELEGATECALL nesting: SIGNER -> call -> DelegateContract -> delegatecall -> ServiceContract -> HTS
    private static final String DELEGATE_CONTRACT = "DelegateContract";
    // STATICCALL nesting: SIGNER -> call -> StaticContract -> staticcall -> ServiceContract -> HTS
    private static final String STATIC_CONTRACT = "StaticContract";
    private static final String SERVICE_CONTRACT = "ServiceContract";

    @HapiTest
    final Stream<DynamicTest> regularNestedCallToUnauthorizedHtsMintReverts() {
        final var txn = "regularNestedCallTxn";
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                uploadInitCode(MINT_TOKEN, NESTED_BURN),
                contractCreate(MINT_TOKEN).gas(GAS_TO_OFFER),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(NESTED_BURN, asHeadlongAddress(getNestedContractAddress(MINT_TOKEN, spec)))
                                .gas(GAS_TO_OFFER),
                        // The token's supply key is a plain key that neither contract holds, so the nested HTS
                        // mint is not authorized; MintToken reverts, unwinding the outer NestedBurn call too.
                        contractCall(
                                        NESTED_BURN,
                                        "burnAfterNestedMint",
                                        BigInteger.ONE,
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        new long[0])
                                .payingWith(GENESIS)
                                .gas(GAS_TO_OFFER)
                                .via(txn)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                // The failed nested mint had no effect on supply.
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(50L));
    }

    @HapiTest
    final Stream<DynamicTest> delegateNestedCallToUnauthorizedHtsMintReverts() {
        final var txn = "delegateNestedCallTxn";
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                uploadInitCode(DELEGATE_CONTRACT, SERVICE_CONTRACT),
                contractCreate(SERVICE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                DELEGATE_CONTRACT, asHeadlongAddress(getNestedContractAddress(SERVICE_CONTRACT, spec))),
                        // The token's supply key was never updated to a delegate-contract key, so the mint
                        // delegatecall is not authorized; ServiceContract reverts and DelegateContract reverts.
                        contractCall(
                                        DELEGATE_CONTRACT,
                                        "mintDelegateCall",
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        BigInteger.ONE)
                                .payingWith(GENESIS)
                                .gas(GAS_TO_OFFER)
                                .via(txn)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(50L));
    }

    @HapiTest
    final Stream<DynamicTest> staticNestedCallToHtsMintReverts() {
        final var txn = "staticNestedCallTxn";
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(0L)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                uploadInitCode(STATIC_CONTRACT, SERVICE_CONTRACT),
                contractCreate(SERVICE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                STATIC_CONTRACT, asHeadlongAddress(getNestedContractAddress(SERVICE_CONTRACT, spec))),
                        // A state-mutating HTS mint executed inside a STATICCALL frame cannot succeed;
                        // ServiceContract reverts and StaticContract reverts with "Static mint call failed!".
                        contractCall(
                                        STATIC_CONTRACT,
                                        "mintStaticCall",
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        BigInteger.ONE)
                                .payingWith(GENESIS)
                                .gas(GAS_TO_OFFER)
                                .via(txn)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(0L));
    }
}
