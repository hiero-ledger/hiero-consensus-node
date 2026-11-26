// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.METADATA_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.SUPPLY_KEY;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import com.hederahashgraph.api.proto.java.TokenInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

/**
 * Token updates through the HTS system contracts cannot inherit the caller's account key (that is only
 * resolved when creating a token). Such an update must revert with bounded gas and leave the token unchanged,
 * whether it is called directly or scheduled through {@code scheduleNative}.
 */
@HapiTestLifecycle
@DisplayName("updateToken with inheritAccountKey")
public class UpdateTokenInheritedKeyTest {
    private static final Address HTS_167 = asHeadlongAddress("0x0000000000000000000000000000000000000167");
    private static final Address HTS_16C = asHeadlongAddress("0x000000000000000000000000000000000000016c");
    private static final Address HSS_16B = asHeadlongAddress("0x000000000000000000000000000000000000016b");
    private static final Address ZERO = asHeadlongAddress(new byte[20]);

    private static final String TOKEN_KEY = "(uint256,(bool,address,bytes,bytes,address))";
    private static final String EXPIRY = "(int64,address,int64)";
    private static final String EXPIRY_V1 = "(uint32,address,uint32)";
    private static final String TOKEN_V1 =
            "(string,string,address,string,bool,uint32,bool," + TOKEN_KEY + "[]," + EXPIRY_V1 + ")";
    private static final String TOKEN_V2 =
            "(string,string,address,string,bool,int64,bool," + TOKEN_KEY + "[]," + EXPIRY_V1 + ")";
    private static final String TOKEN_V3 =
            "(string,string,address,string,bool,int64,bool," + TOKEN_KEY + "[]," + EXPIRY + ")";
    private static final String TOKEN_WITH_METADATA =
            "(string,string,address,string,bool,int64,bool," + TOKEN_KEY + "[]," + EXPIRY + ",bytes)";
    private static final Function UPDATE_KEYS = new Function("updateTokenKeys(address," + TOKEN_KEY + "[])");
    private static final Function UPDATE_INFO_V1 = new Function("updateTokenInfo(address," + TOKEN_V1 + ")");
    private static final Function UPDATE_INFO_V2 = new Function("updateTokenInfo(address," + TOKEN_V2 + ")");
    private static final Function UPDATE_INFO_V3 = new Function("updateTokenInfo(address," + TOKEN_V3 + ")");
    private static final Function UPDATE_INFO_WITH_METADATA =
            new Function("updateTokenInfo(address," + TOKEN_WITH_METADATA + ")");
    private static final Function SCHEDULE_NATIVE =
            new Function("scheduleNative(address,bytes,address)", "(int64,address)");

    private static final int SUPPLY = 16;
    private static final int METADATA = 128;
    private static final long GAS = 3_000_000L;
    // A full-gas halt would use nearly all of GAS; a bounded revert stays far below this
    private static final long MAX_GAS_USED_FOR_REJECTION = 200_000L;

    @Contract(contract = "MakeCalls", creationGas = 3_000_000)
    static SpecContract makeCalls;

    @FungibleToken(
            name = "inheritKeyToken",
            keys = {ADMIN_KEY, SUPPLY_KEY, METADATA_KEY})
    static SpecFungibleToken token;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(token.authorizeContracts(makeCalls)
                .alsoAuthorizing(TokenKeyType.SUPPLY_KEY, TokenKeyType.METADATA_KEY));
    }

    @HapiTest
    @DisplayName("direct updates revert with bounded gas and leave the token unchanged")
    public Stream<DynamicTest> directUpdatesRevert() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var tokenAddress = token.addressOn(spec.targetNetworkOrThrow());
            final var before = tokenInfo(spec);
            final var inheritedSupply = new Tuple[] {tokenKey(SUPPLY, true, new byte[0])};
            final var mixed =
                    new Tuple[] {tokenKey(SUPPLY, false, new byte[32]), tokenKey(METADATA, true, new byte[0])};

            assertRejected(spec, "keys167", HTS_167, UPDATE_KEYS.encodeCallWithArgs(tokenAddress, inheritedSupply));
            assertRejected(spec, "keys16c", HTS_16C, UPDATE_KEYS.encodeCallWithArgs(tokenAddress, mixed));
            assertRejected(
                    spec,
                    "info167",
                    HTS_167,
                    UPDATE_INFO_V3.encodeCallWithArgs(tokenAddress, tokenV3("Renamed", inheritedSupply)));
            assertRejected(
                    spec,
                    "info16c",
                    HTS_16C,
                    UPDATE_INFO_WITH_METADATA.encodeCallWithArgs(tokenAddress, tokenWithMetadata("Renamed", mixed)));

            final var after = tokenInfo(spec);
            assertEquals(before.getName(), after.getName());
            assertEquals(before.getSupplyKey(), after.getSupplyKey());
            assertEquals(before.getMetadataKey(), after.getMetadataKey());
        }));
    }

    @HapiTest
    @DisplayName("scheduleNative reverts with bounded gas instead of scheduling the update, for every accepted variant")
    public Stream<DynamicTest> scheduledUpdateReverts() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var net = spec.targetNetworkOrThrow();
            final var tokenAddress = token.addressOn(net);
            final var payer = makeCalls.addressOn(net);
            final var inherited = new Tuple[] {tokenKey(SUPPLY, true, new byte[0])};
            final var explicit = new Tuple[] {tokenKey(SUPPLY, false, new byte[32])};
            final var before = tokenInfo(spec);

            for (final var variant : new String[] {"V1", "V2", "V3"}) {
                final var rejected = updateInfoCall(variant, tokenAddress, "Renamed" + variant, inherited);
                assertRejected(
                        spec,
                        "schedule" + variant,
                        HSS_16B,
                        SCHEDULE_NATIVE.encodeCallWithArgs(HTS_167, rejected, payer));

                // Control: the same route with an explicit key does schedule, so the rejection above is not incidental
                final var accepted = updateInfoCall(variant, tokenAddress, "Scheduled" + variant, explicit);
                assertScheduled(
                        spec,
                        "scheduleControl" + variant,
                        SCHEDULE_NATIVE.encodeCallWithArgs(HTS_167, accepted, payer));
            }

            final var after = tokenInfo(spec);
            assertEquals(before.getName(), after.getName());
            assertEquals(before.getSupplyKey(), after.getSupplyKey());
        }));
    }

    private static byte[] updateInfoCall(
            final String variant, final Address tokenAddress, final String name, final Tuple[] keys) {
        return switch (variant) {
            case "V1" ->
                UPDATE_INFO_V1
                        .encodeCallWithArgs(tokenAddress, tokenV1V2(name, keys))
                        .array();
            case "V2" ->
                UPDATE_INFO_V2
                        .encodeCallWithArgs(tokenAddress, tokenV1V2(name, keys))
                        .array();
            default ->
                UPDATE_INFO_V3
                        .encodeCallWithArgs(tokenAddress, tokenV3(name, keys))
                        .array();
        };
    }

    private static void assertScheduled(final HapiSpec spec, final String label, final java.nio.ByteBuffer callData) {
        final var txn = "inheritKey-" + label;
        final var success = new AtomicReference<Boolean>();
        final var returned = new AtomicReference<byte[]>();
        allRunFor(
                spec,
                makeCalls
                        .call("makeCallWithoutAmount", HSS_16B, callData.array())
                        .gas(GAS)
                        .via(txn)
                        .exposingResultTo(res -> {
                            success.set((Boolean) res[0]);
                            returned.set((byte[]) res[1]);
                        }),
                // the schedule create is externalized as a child record
                getTxnRecord(txn).andAllChildRecords().hasNonStakingChildRecordCount(1));
        assertTrue(success.get(), label + ": scheduling an update with an explicit key must succeed");
        final Tuple scheduleResult = SCHEDULE_NATIVE.decodeReturn(returned.get());
        assertEquals((long) SUCCESS.getNumber(), (long) scheduleResult.get(0), label + ": schedule status");
        assertFalse(ZERO.equals(scheduleResult.get(1)), label + ": a schedule must have been created");
    }

    private static void assertRejected(
            final HapiSpec spec, final String label, final Address target, final java.nio.ByteBuffer callData) {
        final var txn = "inheritKey-" + label;
        final var innerSuccess = new AtomicReference<Boolean>();
        final var gasUsed = new AtomicLong();
        allRunFor(
                spec,
                makeCalls
                        .call("makeCallWithoutAmount", target, callData.array())
                        .gas(GAS)
                        .via(txn)
                        .exposingResultTo(res -> innerSuccess.set((Boolean) res[0])),
                getTxnRecord(txn).exposingTo(record -> {
                    assertEquals(SUCCESS, record.getReceipt().getStatus());
                    gasUsed.set(record.getContractCallResult().getGasUsed());
                }),
                // nothing is dispatched: no token update and no schedule create child record
                getTxnRecord(txn).andAllChildRecords().hasNonStakingChildRecordCount(0));
        assertFalse(innerSuccess.get(), label + ": the system contract call must fail");
        assertTrue(
                gasUsed.get() < MAX_GAS_USED_FOR_REJECTION,
                label + ": rejection must not consume the forwarded gas, used " + gasUsed.get());
    }

    private static TokenInfo tokenInfo(final HapiSpec spec) {
        final var op = getTokenInfo(token.name());
        allRunFor(spec, op);
        return op.getResponse().getTokenGetInfo().getTokenInfo();
    }

    private static Tuple tokenKey(final int keyType, final boolean inherit, final byte[] ed25519) {
        return Tuple.of(BigInteger.valueOf(keyType), Tuple.of(inherit, ZERO, ed25519, new byte[0], ZERO));
    }

    private static Tuple tokenV1V2(final String name, final Tuple[] keys) {
        return Tuple.from(name, "", ZERO, "", false, 0L, false, keys, Tuple.of(0L, ZERO, 0L));
    }

    private static Tuple tokenV3(final String name, final Tuple[] keys) {
        return Tuple.from(name, "", ZERO, "", false, 0L, false, keys, Tuple.of(0L, ZERO, 0L));
    }

    private static Tuple tokenWithMetadata(final String name, final Tuple[] keys) {
        return Tuple.from(name, "", ZERO, "", false, 0L, false, keys, Tuple.of(0L, ZERO, 0L), new byte[0]);
    }
}
