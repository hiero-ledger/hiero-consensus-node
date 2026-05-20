// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token.associate;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
@Tag(SMART_CONTRACT)
public class AssociationsLimitsTest {

    private static final int TOKENS_TO_CREATE = 11;
    private static final String ACCOUNT = "AssociateAccount";
    private static final String TOKEN_TREASURY = "Treasury";
    private static final String TOKEN = "Token";
    private static final String TX_NAME = "associateTokensTx";

    static final AtomicReference<Address> accountAddress = new AtomicReference<>();
    static final List<Address> tokenAddresses = new ArrayList<>();

    @BeforeAll
    public static void setup(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(flattened(
                // create target account
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(ACCOUNT)
                        .key(SECP_256K1_SOURCE_KEY)
                        .balance(ONE_HUNDRED_HBARS)
                        .exposingEvmAddressTo(accountAddress::set),
                // create tokens
                cryptoCreate(TOKEN_TREASURY),
                IntStream.range(0, TOKENS_TO_CREATE)
                        .mapToObj(i -> tokenCreate(TOKEN + i)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1L)
                                .supplyKey(TOKEN_TREASURY)
                                .adminKey(TOKEN_TREASURY)
                                .treasury(TOKEN_TREASURY)
                                .exposingAddressTo(tokenAddresses::add))
                        .toList()));
    }

    private static String messageToHex(String message) {
        return String.format("0x%040x", new BigInteger(1, message.getBytes()));
    }

    public Stream<DynamicTest> associateDissociateTokensCall(final String functionName) {
        final var function = getABIFor(FUNCTION, functionName, "IHederaTokenService");
        return hapiTest(
                contractCallWithFunctionAbi(
                                "0x0000000000000000000000000000000000000167",
                                function,
                                accountAddress.get(),
                                tokenAddresses.toArray(Address[]::new))
                        .gas(10_000_000) // each association = ~700_000 gas
                        .payingWith(ACCOUNT)
                        .via(TX_NAME)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                getTxnRecord(TX_NAME)
                        .exposingTo(e -> assertEquals(
                                messageToHex(TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED.toString()),
                                e.getContractCallResult().getErrorMessage())));
    }

    @HapiTest
    public Stream<DynamicTest> associateTokensLimitTest() {
        return associateDissociateTokensCall("associateTokens");
    }

    @HapiTest
    public Stream<DynamicTest> dissociateTokensLimitTest() {
        return associateDissociateTokensCall("dissociateTokens");
    }
}
