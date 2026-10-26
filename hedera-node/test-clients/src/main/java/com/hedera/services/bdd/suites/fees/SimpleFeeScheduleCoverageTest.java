// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.SIMPLE_FEE_SCHEDULE;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.ContextRequirement;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.SysFileOverrideOp;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;
import org.hiero.hapi.support.fees.ServiceFeeSchedule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;

/**
 * Verifies that an update of the simple fee schedule file is rejected atomically when the new schedule is a
 * complete, well-formed schedule the registered calculators still cannot evaluate: it omits a function definition,
 * an extra reference a fee calculator charges, or an extra that a handler reads directly. "Atomically" means the
 * file write itself rolls back with the dispatch, so the file content and the schedule in use are both left
 * exactly as they were. Each spec still carries a {@link SysFileOverrideOp} safety net in case that rollback
 * itself regresses, so a failed assertion here does not leave the shared network's fee schedule mutated for
 * later tests.
 */
public class SimpleFeeScheduleCoverageTest {
    private static final String ORIGINAL = "originalSimpleFeeSchedule";
    private static final String MODIFIED = "modifiedSimpleFeeSchedule";
    private static final String PAYER = "payer";

    @LeakyHapiTest(requirement = ContextRequirement.NO_CONCURRENT_CREATIONS)
    final Stream<DynamicTest> scheduleWithoutAFunctionDefinitionIsRejected() {
        return transferAfterRejectedInstall(withoutFunction(HederaFunctionality.CRYPTO_TRANSFER));
    }

    @LeakyHapiTest(requirement = ContextRequirement.NO_CONCURRENT_CREATIONS)
    final Stream<DynamicTest> scheduleWithoutAnExtraReferenceTheCalculatorNeedsIsRejected() {
        return transferAfterRejectedInstall(withoutExtraRef(HederaFunctionality.CRYPTO_TRANSFER, Extra.ACCOUNTS));
    }

    @LeakyHapiTest(requirement = ContextRequirement.NO_CONCURRENT_CREATIONS)
    final Stream<DynamicTest> scheduleWithoutAnExtraDefinitionAHandlerReadsIsRejected() {
        return hapiTest(
                safetyNetRestore(),
                cryptoCreate(PAYER).balance(100 * ONE_HBAR),
                cryptoCreate("receiver").maxAutomaticTokenAssociations(0),
                tokenCreate("token").treasury(PAYER).initialSupply(100L),
                getFileContents(SIMPLE_FEE_SCHEDULE).saveToRegistry(ORIGINAL),
                withOpContext((spec, opLog) -> {
                    final var original = FeeSchedule.PROTOBUF.parse(
                            Bytes.wrap(spec.registry().getBytes(ORIGINAL)));
                    final var mutated = FeeSchedule.PROTOBUF.toBytes(
                            withoutExtra(Extra.TOKEN_ASSOCIATE).apply(original));
                    spec.registry().saveBytes(MODIFIED, ByteString.copyFrom(mutated.toByteArray()));
                }),
                rejectedUpdate(),
                unchangedFileContent(),
                tokenAirdrop(moving(1L, "token").between(PAYER, "receiver"))
                        .payingWith(PAYER)
                        .hasKnownStatus(ResponseCodeEnum.SUCCESS));
    }

    private static UnaryOperator<FeeSchedule> withoutExtra(final Extra extra) {
        return schedule -> schedule.copyBuilder()
                .extras(schedule.extras().stream()
                        .filter(def -> def.name() != extra)
                        .toList())
                .services(schedule.services().stream()
                        .map((ServiceFeeSchedule service) -> service.copyBuilder()
                                .schedule(service.schedule().stream()
                                        .map((ServiceFeeDefinition def) -> def.copyBuilder()
                                                .extras(def.extras().stream()
                                                        .filter(ref -> ref.name() != extra)
                                                        .toList())
                                                .build())
                                        .toList())
                                .build())
                        .toList())
                .node(schedule.node()
                        .copyBuilder()
                        .extras(schedule.node().extras().stream()
                                .filter(ref -> ref.name() != extra)
                                .toList())
                        .build())
                .build();
    }

    private static Stream<DynamicTest> transferAfterRejectedInstall(final UnaryOperator<FeeSchedule> mutation) {
        return hapiTest(
                safetyNetRestore(),
                cryptoCreate(PAYER).balance(10 * ONE_HBAR),
                getFileContents(SIMPLE_FEE_SCHEDULE).saveToRegistry(ORIGINAL),
                withOpContext((spec, opLog) -> {
                    final var original = FeeSchedule.PROTOBUF.parse(
                            Bytes.wrap(spec.registry().getBytes(ORIGINAL)));
                    final var mutated = FeeSchedule.PROTOBUF.toBytes(mutation.apply(original));
                    spec.registry().saveBytes(MODIFIED, ByteString.copyFrom(mutated.toByteArray()));
                }),
                rejectedUpdate(),
                unchangedFileContent(),
                cryptoTransfer(tinyBarsFromTo(PAYER, GENESIS, 1L))
                        .payingWith(PAYER)
                        .hasKnownStatus(ResponseCodeEnum.SUCCESS));
    }

    /**
     * Snapshots the file's current contents and unconditionally restores them when the spec completes, even if an
     * earlier op in the spec fails: {@code HapiSpec.exec} stops submitting ops on the first failure, so a failed
     * {@code rejectedUpdate()} assertion (for example because the rollback this suite tests for has regressed)
     * would otherwise leave the shared network's fee schedule mutated for every later test.
     */
    private static SpecOperation safetyNetRestore() {
        return new SysFileOverrideOp(SysFileOverrideOp.Target.SIMPLE_FEES, () -> null);
    }

    /**
     * Submits the mutated schedule (saved to the registry as {@value #MODIFIED} by the caller) as a single
     * FileUpdate, which every schedule used in this suite fits in: it is rejected with {@code INVALID_FEE_FILE},
     * and the dispatch (including the file write) rolls back.
     */
    private static SpecOperation rejectedUpdate() {
        return withOpContext((spec, opLog) -> allRunFor(
                spec,
                fileUpdate(SIMPLE_FEE_SCHEDULE)
                        .contents(s -> ByteString.copyFrom(s.registry().getBytes(MODIFIED)))
                        .payingWith(GENESIS)
                        .hasKnownStatus(ResponseCodeEnum.INVALID_FEE_FILE)));
    }

    /**
     * Proves the rejected update never took effect: the file still holds exactly the bytes saved as ORIGINAL.
     * Compares raw bytes directly ({@code HapiGetFileContents.hasContents} decodes both sides through the default
     * charset before comparing, which can equate distinct binary content) since the schedule is binary protobuf,
     * not text.
     */
    private static SpecOperation unchangedFileContent() {
        return withOpContext((spec, opLog) -> {
            final var original = spec.registry().getBytes(ORIGINAL);
            allRunFor(
                    spec,
                    getFileContents(SIMPLE_FEE_SCHEDULE)
                            .consumedBy(actual -> Assertions.assertArrayEquals(
                                    original, actual, "Simple fee schedule file content changed")));
        });
    }

    private static UnaryOperator<FeeSchedule> withoutFunction(final HederaFunctionality function) {
        return schedule -> schedule.copyBuilder()
                .services(schedule.services().stream()
                        .map(service -> service.copyBuilder()
                                .schedule(service.schedule().stream()
                                        .filter(def -> def.name() != function)
                                        .toList())
                                .build())
                        .filter(service -> !service.schedule().isEmpty())
                        .toList())
                .build();
    }

    private static UnaryOperator<FeeSchedule> withoutExtraRef(final HederaFunctionality function, final Extra extra) {
        return schedule -> schedule.copyBuilder()
                .services(schedule.services().stream()
                        .map((ServiceFeeSchedule service) -> service.copyBuilder()
                                .schedule(service.schedule().stream()
                                        .map((ServiceFeeDefinition def) -> def.name() == function
                                                ? def.copyBuilder()
                                                        .extras(def.extras().stream()
                                                                .filter(ref -> ref.name() != extra)
                                                                .toList())
                                                        .build()
                                                : def)
                                        .toList())
                                .build())
                        .toList())
                .build();
    }
}
