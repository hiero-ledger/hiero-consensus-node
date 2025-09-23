// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.keyTupleFor;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.dsl.entities.SpecContract.VARIANT_16C;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType.FREEZE_KEY;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType.METADATA_KEY;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType.SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.KEY_NOT_PROVIDED;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("getTokenKey")
@SuppressWarnings("java:S1192")
@HapiTestLifecycle
public class GetTokenKeyPrecompileTest {
    private static final Address ZERO_ADDRESS = asHeadlongAddress(new byte[20]);

    @Contract(contract = "UpdateTokenInfoContract", creationGas = 4_000_000L)
    static SpecContract getTokenKeyContract;

    @Contract(contract = "UpdateTokenInfoContract", creationGas = 4_000_000L, variant = VARIANT_16C)
    static SpecContract getTokenKeyContract16c;

    @NonFungibleToken(
            numPreMints = 1,
            keys = {ADMIN_KEY, SpecTokenKey.SUPPLY_KEY, SpecTokenKey.METADATA_KEY})
    static SpecNonFungibleToken nonFungibleToken;

    @HapiTest
    @DisplayName("can get a token's supply key via static call")
    @Tag(MATS)
    public Stream<DynamicTest> canGetSupplyKeyViaStaticCall() {
        return hapiTest(
                nonFungibleToken.doWith(token -> getTokenKeyContract
                        .staticCall("getKeyFromToken", nonFungibleToken, SUPPLY_KEY.asBigInteger())
                        .andAssert(query -> query.has(resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, "getKeyFromToken", "UpdateTokenInfoContract"),
                                        isLiteralResult(new Object[] {keyTupleFor(token.supplyKeyOrThrow())}))))),
                nonFungibleToken.doWith(token -> getTokenKeyContract16c
                        .staticCall("getKeyFromToken", nonFungibleToken, SUPPLY_KEY.asBigInteger())
                        .andAssert(query -> query.has(resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, "getKeyFromToken", "UpdateTokenInfoContract"),
                                        isLiteralResult(new Object[] {keyTupleFor(token.supplyKeyOrThrow())}))))));
    }

    @HapiTest
    @DisplayName("can't get a token's metadata key via static call for HTS 0x167 but can for 0x16c")
    @Tag(MATS)
    public Stream<DynamicTest> canGetMetadataKeyViaStaticCall() {
        return hapiTest(
                nonFungibleToken.doWith(token -> getTokenKeyContract
                        .staticCall("getKeyFromToken", nonFungibleToken, METADATA_KEY.asBigInteger())
                        .andAssert(query -> query.hasAnswerOnlyPrecheck(CONTRACT_REVERT_EXECUTED))),
                nonFungibleToken.doWith(token -> getTokenKeyContract16c
                        .staticCall("getKeyFromToken", nonFungibleToken, METADATA_KEY.asBigInteger())
                        .andAssert(query -> query.has(resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, "getKeyFromToken", "UpdateTokenInfoContract"),
                                        isLiteralResult(new Object[] {keyTupleFor(token.metadataKeyOrThrow())}))))));
    }

    @HapiTest
    @DisplayName("cannot get a nonsense key type")
    public Stream<DynamicTest> cannotGetNonsenseKeyType() {
        return hapiTest(
                getTokenKeyContract
                        .call("getKeyFromToken", nonFungibleToken, BigInteger.valueOf(123L))
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, KEY_NOT_PROVIDED)),
                getTokenKeyContract16c
                        .call("getKeyFromToken", nonFungibleToken, BigInteger.valueOf(123L))
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, KEY_NOT_PROVIDED)));
    }

    @HapiTest
    @DisplayName("cannot get a key from a missing token")
    public Stream<DynamicTest> cannotGetMissingTokenKey() {
        return hapiTest(
                getTokenKeyContract
                        .call("getKeyFromToken", ZERO_ADDRESS, SUPPLY_KEY.asBigInteger())
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_TOKEN_ID)),
                getTokenKeyContract16c
                        .call("getKeyFromToken", ZERO_ADDRESS, SUPPLY_KEY.asBigInteger())
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_TOKEN_ID)));
    }

    @HapiTest
    @DisplayName("cannot get a key not set on the token")
    public Stream<DynamicTest> cannotGetUnsetTokenKey() {
        return hapiTest(
                getTokenKeyContract
                        .call("getKeyFromToken", nonFungibleToken, FREEZE_KEY.asBigInteger())
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, KEY_NOT_PROVIDED)),
                getTokenKeyContract16c
                        .call("getKeyFromToken", nonFungibleToken, FREEZE_KEY.asBigInteger())
                        .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, KEY_NOT_PROVIDED)));
    }
}
