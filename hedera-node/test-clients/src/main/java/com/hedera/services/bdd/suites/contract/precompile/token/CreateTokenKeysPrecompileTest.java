// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.namedHapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Characterizes the key types accepted by the HTS create functions. An entry whose key type has no defined bit (zero
 * or only undefined bits) selects no key, so the whole create is rejected with INVALID_TRANSACTION_BODY, whereas an
 * empty key list, or undefined bits next to a defined one, still create the token.
 */
@Tag(SMART_CONTRACT)
@DisplayName("create token key types")
@SuppressWarnings("java:S1192")
@HapiTestLifecycle
public class CreateTokenKeysPrecompileTest {
    private static final long GAS_NEEDED = 1_000_000L;
    private static final long HTS_FEE = 20 * ONE_HBAR;
    private static final Address ZERO_ADDRESS = asHeadlongAddress(new byte[20]);
    private static final BigInteger SUPPLY_KEY_TYPE = BigInteger.valueOf(0x10);
    private static final BigInteger METADATA_KEY_TYPE = BigInteger.valueOf(0x80);
    private static final String FUNGIBLE_167 = "createFungibleToken";
    private static final String NON_FUNGIBLE_167 = "createNonFungibleToken";
    private static final String FUNGIBLE_16C = "createFungibleTokenWithMetadata";

    // Forwards an arbitrary TokenKey[] to the create functions at 0x167 and 0x16c, returning the response code of the
    // precompile
    @Contract(contract = "TokenKeysContract", creationGas = 2_000_000L)
    static SpecContract createTokenKeysContract;

    @HapiTest
    @DisplayName("a defined key type creates a fungible token at 0x167")
    public Stream<DynamicTest> definedKeyTypeCreatesFungibleToken() {
        return Stream.of(creating("definedKeyFungible167", FUNGIBLE_167, false, newKey ->
                new Tuple[] {keyEntry(SUPPLY_KEY_TYPE, newKey)}));
    }

    @HapiTest
    @DisplayName("a defined key type creates a non-fungible token at 0x167")
    public Stream<DynamicTest> definedKeyTypeCreatesNonFungibleToken() {
        return Stream.of(creating("definedKeyNonFungible167", NON_FUNGIBLE_167, false, newKey ->
                new Tuple[] {keyEntry(SUPPLY_KEY_TYPE, newKey)}));
    }

    @HapiTest
    @DisplayName("an empty key list still creates a fungible token at 0x167")
    public Stream<DynamicTest> emptyKeyListCreatesFungibleToken() {
        return Stream.of(creating("emptyKeyList167", FUNGIBLE_167, false, _ -> new Tuple[0]));
    }

    @HapiTest
    @DisplayName("undefined bits next to a defined one still create a fungible token at 0x167")
    public Stream<DynamicTest> strayUndefinedBitNextToDefinedBitCreatesFungibleToken() {
        return Stream.of(creating("strayBit167", FUNGIBLE_167, false, newKey ->
                new Tuple[] {keyEntry(BigInteger.valueOf(0x110), newKey)}));
    }

    @HapiTest
    @DisplayName("the metadata key type is defined at 0x16c")
    public Stream<DynamicTest> metadataKeyTypeCreatesFungibleTokenAt16c() {
        return Stream.of(creating(
                "metadataKey16c", FUNGIBLE_16C, false, newKey -> new Tuple[] {keyEntry(METADATA_KEY_TYPE, newKey)}));
    }

    @HapiTest
    @DisplayName("keyType zero and each undefined bit are rejected when creating a fungible token at 0x167")
    public Stream<DynamicTest> undefinedBitsAreRejectedForFungibleToken() {
        // At 0x167 bits 0x80 and up have no key; 0x80 is only the metadata key at 0x16c
        return rejectingEachBit(FUNGIBLE_167, 7);
    }

    @HapiTest
    @DisplayName("keyType zero and each undefined bit are rejected when creating a non-fungible token at 0x167")
    public Stream<DynamicTest> undefinedBitsAreRejectedForNonFungibleToken() {
        return rejectingEachBit(NON_FUNGIBLE_167, 7);
    }

    @HapiTest
    @DisplayName("keyType zero and each undefined bit are rejected when creating a fungible token at 0x16c")
    public Stream<DynamicTest> undefinedBitsAreRejectedForFungibleTokenAt16c() {
        return rejectingEachBit(FUNGIBLE_16C, 8);
    }

    @HapiTest
    @DisplayName("a valid entry next to an entry with an undefined bit is rejected as a whole")
    public Stream<DynamicTest> validEntryWithUndefinedEntryIsRejected() {
        return Stream.of(creating("validAndUndefined167", FUNGIBLE_167, true, newKey ->
                new Tuple[] {keyEntry(SUPPLY_KEY_TYPE, newKey), keyEntry(BigInteger.valueOf(0x100), newKey)}));
    }

    private Stream<DynamicTest> rejectingEachBit(@NonNull final String function, final int firstBit) {
        return Stream.concat(
                        Stream.of(BigInteger.ZERO),
                        IntStream.range(firstBit, 16).mapToObj(BigInteger.ONE::shiftLeft))
                .map(bit -> creating(function + "UndefinedBit" + bit.toString(16), function, true, newKey ->
                        new Tuple[] {keyEntry(bit, newKey)}));
    }

    /**
     * Calls the given create function with the entries. The contract does not revert on a rejection, so the call must
     * succeed and return the response code of the precompile, SUCCESS or, if {@code rejected}, INVALID_TRANSACTION_BODY,
     * which is also the status of the child record.
     */
    private DynamicTest creating(
            @NonNull final String tag,
            @NonNull final String function,
            final boolean rejected,
            @NonNull final Function<byte[], Tuple[]> entries) {
        final var newKey = "createTokenKey";
        final var txnName = tag + "Txn";
        return namedHapiTest(
                tag,
                newKeyNamed(newKey).shape(SIMPLE),
                withOpContext((spec, _) -> allRunFor(
                        spec,
                        createTokenKeysContract
                                .call(function, (Object) entries.apply(ed25519Of(spec, newKey)))
                                .via(txnName)
                                .sending(HTS_FEE)
                                .gas(GAS_NEEDED))),
                returnedByCreate(txnName, function, rejected ? INVALID_TRANSACTION_BODY : SUCCESS));
    }

    private static HapiGetTxnRecord returnedByCreate(
            @NonNull final String txnName, @NonNull final String function, @NonNull final ResponseCodeEnum status) {
        return getTxnRecord(txnName)
                .andAllChildRecords()
                .hasPriority(recordWith()
                        .status(SUCCESS)
                        .contractCallResult(resultWith()
                                .resultViaFunctionName(
                                        function, "TokenKeysContract", _ -> returned -> hasCode(returned, status))))
                .hasChildRecords(recordWith().status(status));
    }

    // The create functions return (responseCode, tokenAddress); only the code is checked, since SUCCESS means created
    private static Optional<Throwable> hasCode(
            @NonNull final Object[] returned, @NonNull final ResponseCodeEnum status) {
        return (long) returned[0] == status.getNumber()
                ? Optional.empty()
                : Optional.of(new AssertionError("Expected " + status + " but returned " + Arrays.toString(returned)));
    }

    private static byte[] ed25519Of(@NonNull final HapiSpec spec, @NonNull final String keyName) {
        return spec.registry().getKey(keyName).getEd25519().toByteArray();
    }

    private static Tuple keyEntry(@NonNull final BigInteger keyType, @NonNull final byte[] ed25519) {
        return Tuple.of(keyType, Tuple.of(false, ZERO_ADDRESS, ed25519, new byte[0], ZERO_ADDRESS));
    }
}
