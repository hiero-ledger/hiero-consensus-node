// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.namedHapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Characterizes scheduling the HTS token create and updateTokenInfo functions with {@code scheduleNative()}. A
 * scheduled call with a {@code TokenKey} whose key type selects no key (zero, or only undefined bits) should not
 * create a schedule, since the scheduled HAPI transaction has no key type and would silently ignore the entry when it
 * executes. For now the schedule is still created, as scheduleNative() does not yet translate the pre-empting status
 * of the inner call. The updateTokenKeys functions are not on the whitelist of schedulable calls at all.
 * <p>
 * Every scheduled call needs a distinct body, as the schedule service rejects a schedule identical to an existing one
 * with IDENTICAL_SCHEDULE_ALREADY_CREATED, which would look like a rejection of the key type. So each call carries a
 * valid entry with a key of its own, next to the entry under test.
 */
@Tag(SMART_CONTRACT)
@DisplayName("scheduleNative with token key types")
@SuppressWarnings("java:S1192")
@HapiTestLifecycle
public class ScheduleTokenKeysTest {
    private static final long GAS_NEEDED = 1_000_000L;
    private static final Address ZERO_ADDRESS = asHeadlongAddress(new byte[20]);
    private static final BigInteger SUPPLY_KEY_TYPE = BigInteger.valueOf(0x10);
    private static final String NEW_KEY = "scheduledTokenKey";
    private static final String UPDATE_TOKEN_KEYS = "scheduleUpdateTokenKeys";
    private static final String UPDATE_TOKEN_INFO = "scheduleUpdateTokenInfo";
    private static final String CREATE_FUNGIBLE_TOKEN = "scheduleCreateFungibleToken";

    // Forwards an arbitrary TokenKey[] to the HTS functions through scheduleNative()
    @Contract(contract = "TokenKeysContract", creationGas = 2_000_000L)
    static SpecContract tokenKeysContract;

    @FungibleToken(name = "scheduledUpdateTarget")
    static SpecFungibleToken scheduledUpdateTarget;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(scheduledUpdateTarget.authorizeContracts(tokenKeysContract));
    }

    @HapiTest
    @DisplayName("a defined key type can be scheduled with updateTokenInfo")
    public Stream<DynamicTest> definedKeyTypeSchedulesUpdateTokenInfo() {
        return Stream.of(scheduling("definedUpdateInfo", UPDATE_TOKEN_INFO, true, ScheduleTokenKeysTest::definedEntry));
    }

    @HapiTest
    @DisplayName("a defined key type can be scheduled with createFungibleToken")
    public Stream<DynamicTest> definedKeyTypeSchedulesCreateFungibleToken() {
        return Stream.of(scheduling("definedCreate", CREATE_FUNGIBLE_TOKEN, true, ScheduleTokenKeysTest::definedEntry));
    }

    @HapiTest
    @DisplayName("updateTokenKeys cannot be scheduled, even with a defined key type")
    public Stream<DynamicTest> updateTokenKeysIsNotSchedulable() {
        // The call is not on the whitelist of schedulable inner calls, so the scheduleNative() call itself fails
        return Stream.of(namedHapiTest(
                "updateTokenKeysNotSchedulable",
                newKeyNamed(NEW_KEY).shape(SIMPLE),
                withOpContext((spec, _) -> allRunFor(
                        spec,
                        contractCall(
                                        "TokenKeysContract",
                                        UPDATE_TOKEN_KEYS,
                                        arguments(spec, UPDATE_TOKEN_KEYS, definedEntry(ed25519Of(spec, NEW_KEY))))
                                .gas(GAS_NEEDED)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)))));
    }

    @HapiTest
    @DisplayName("keyType zero or only undefined bits are still scheduled with updateTokenInfo")
    public Stream<DynamicTest> undefinedKeyTypesAreNotScheduledWithUpdateTokenInfo() {
        return rejectingUndefinedKeyTypes("undefinedUpdateInfo", UPDATE_TOKEN_INFO);
    }

    @HapiTest
    @DisplayName("keyType zero or only undefined bits are still scheduled with createFungibleToken")
    public Stream<DynamicTest> undefinedKeyTypesAreNotScheduledWithCreateFungibleToken() {
        return rejectingUndefinedKeyTypes("undefinedCreate", CREATE_FUNGIBLE_TOKEN);
    }

    // Zero, 0x80 (the metadata key, defined only at 0x16c) and 0x100 (not defined at all) all select no key at 0x167.
    private Stream<DynamicTest> rejectingUndefinedKeyTypes(@NonNull final String tag, @NonNull final String function) {
        return Stream.of(BigInteger.ZERO, BigInteger.valueOf(0x80), BigInteger.valueOf(0x100))
                // The schedule is expected to be created for now, although a direct call is rejected with INVALID_TRANSACTION_BODY:
                // scheduleNative() ignores the pre-empting status of the inner call. Flip the expectation to false when the
                // validation of the translated inner call is added to scheduleNative().
                .map(keyType -> scheduling(tag + keyType.toString(16), function, true, newKey ->
                        new Tuple[] {keyEntry(SUPPLY_KEY_TYPE, newKey), keyEntry(keyType, newKey)}));
    }

    /**
     * Calls the given schedule function with the entries. The contract does not revert if scheduleNative() fails, so
     * the call itself must succeed, and it returns whether a schedule was created, which is expected only if
     * {@code scheduled}.
     */
    private DynamicTest scheduling(
            @NonNull final String tag,
            @NonNull final String function,
            final boolean scheduled,
            @NonNull final Function<byte[], Tuple[]> entries) {
        final var txnName = tag + "Txn";
        return namedHapiTest(
                tag,
                newKeyNamed(NEW_KEY).shape(SIMPLE),
                withOpContext((spec, _) -> allRunFor(
                        spec,
                        tokenKeysContract
                                .call(function, arguments(spec, function, entries.apply(ed25519Of(spec, NEW_KEY))))
                                .via(txnName)
                                .gas(GAS_NEEDED))),
                getTxnRecord(txnName)
                        .hasPriority(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .resultViaFunctionName(
                                                function,
                                                "TokenKeysContract",
                                                _ -> returned -> hasScheduled(returned, scheduled)))));
    }

    private static Tuple[] definedEntry(@NonNull final byte[] newKey) {
        return new Tuple[] {keyEntry(SUPPLY_KEY_TYPE, newKey)};
    }

    // The update functions take the token to update first, the create function only the keys
    private static Object[] arguments(
            @NonNull final HapiSpec spec, @NonNull final String function, @NonNull final Tuple[] entries) {
        return CREATE_FUNGIBLE_TOKEN.equals(function)
                ? new Object[] {entries}
                : new Object[] {asHeadlongAddress(tokenAddress(spec)), entries};
    }

    // The schedule functions return (scheduled, scheduleAddress); an address is expected exactly when scheduled
    private static Optional<Throwable> hasScheduled(@NonNull final Object[] returned, final boolean scheduled) {
        final var hasAddress = !ZERO_ADDRESS.equals(returned[1]);
        return (boolean) returned[0] == scheduled && hasAddress == scheduled
                ? Optional.empty()
                : Optional.of(new AssertionError(
                        "Expected scheduled=" + scheduled + " but returned " + Arrays.toString(returned)));
    }

    private static byte[] tokenAddress(@NonNull final HapiSpec spec) {
        return asAddress(spec.registry().getTokenID(scheduledUpdateTarget.name()));
    }

    private static byte[] ed25519Of(@NonNull final HapiSpec spec, @NonNull final String keyName) {
        return spec.registry().getKey(keyName).getEd25519().toByteArray();
    }

    private static Tuple keyEntry(@NonNull final BigInteger keyType, @NonNull final byte[] ed25519) {
        return Tuple.of(keyType, Tuple.of(false, ZERO_ADDRESS, ed25519, new byte[0], ZERO_ADDRESS));
    }
}
