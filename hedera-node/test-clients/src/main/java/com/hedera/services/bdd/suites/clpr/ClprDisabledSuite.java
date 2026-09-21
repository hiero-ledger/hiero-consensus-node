// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetEndpointManifest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCloseChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprDeregisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRedactMessage;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprSubmitBundle;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.clpr.HapiClprUpdateLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.service.proto.java.ClprServiceGrpc;
import io.grpc.MethodDescriptor;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Verifies that CLPR is inert from every direction a client can reach it while {@code clpr.enabled}
 * is false.
 *
 * <p>The feature has four independent entry points, each with its own guard, and a regression in one
 * is invisible to the others:
 * <ol>
 *   <li><b>Ingest</b> — {@code IngestChecker.assertThrottlingPreconditions} rejects every
 *       functionality in its {@code CLPR_TRANSACTIONS} set with a {@code CLPR_NOT_ENABLED}
 *       precheck, including transactions nested inside an atomic batch.</li>
 *   <li><b>Consensus</b> — {@code AbstractClprHandler.handle} throws {@code CLPR_NOT_ENABLED}
 *       before {@code doHandle}. This is the only guard that runs for a transaction that reached
 *       consensus without passing this node's ingest (a batch inner transaction, a peer node on
 *       different config, an internally submitted bundle).</li>
 *   <li><b>Queries</b> — the two query handlers reject both COST_ANSWER and ANSWER_ONLY.</li>
 *   <li><b>EVM</b> — {@code AbstractClprSystemContract.computeFully} halts every call to the CLPR
 *       router (0x16e) and the three peer-ledger verifiers (0x16f, 0x170, 0x171). These contracts
 *       are registered in {@code ProcessorModule} unconditionally, so the flag is the only thing
 *       standing between a deployed contract and CLPR.</li>
 * </ol>
 *
 * <p>{@code clpr.enabled} already defaults to false on this branch, so a disabled-path assertion
 * passes just as readily against a guard that was never wired up. {@link #clprGateIsLoadBearing()}
 * is the control: it flips the flag on and shows the same calls behave differently, which is what
 * makes the negative results above meaningful.
 */
@Tag(CLPR)
public class ClprDisabledSuite {

    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String CALLER = "caller";
    /** Solidity wrapper that calls the CLPR router at 0x16e; shared with {@code ClprSendMessageSuite}. */
    private static final String CLPR_CONTRACT = "ClprSystemContract";

    private static final long GAS_TO_OFFER = 500_000L;

    /**
     * In embedded mode, a transaction addressed to any node other than the default one bypasses
     * ingest entirely (see {@code AbstractEmbeddedHedera#submit}), which is the only way to reach
     * the consensus-time guard in {@code AbstractClprHandler}.
     */
    private static final String INGEST_BYPASS_NODE = "4";

    /**
     * Every native CLPR system contract, by entity number: the CLPR router (0x16e) plus the
     * BesuQBFT (0x16f), Sei (0x170) and Ethereum (0x171) peer-ledger verifiers. All four extend
     * {@code AbstractClprSystemContract}, so all four must halt identically.
     */
    private static final List<String> CLPR_SYSTEM_CONTRACT_NUMS =
            List.of(String.valueOf(0x16eL), String.valueOf(0x16fL), String.valueOf(0x170L), String.valueOf(0x171L));

    /**
     * Any well-formed calldata will do: the {@code clpr.enabled} guard runs in {@code computeFully}
     * before the selector is dispatched, so the halt cannot depend on the function being real.
     */
    private static final String PROBE_ABI = "{\"name\":\"verifyConfig\","
            + "\"inputs\":[{\"name\":\"proofBytes\",\"type\":\"bytes\"}],"
            + "\"outputs\":[{\"name\":\"\",\"type\":\"bytes\"}],"
            + "\"stateMutability\":\"view\",\"type\":\"function\"}";

    /**
     * CLPR functionalities deliberately absent from the HAPI assertions below because they have no
     * {@code ClprService} RPC and no BDD verb: a node submits them internally. They are still gated
     * at ingest ({@code IngestChecker.CLPR_TRANSACTIONS} includes them) so that a hand-rolled body
     * cannot be submitted by a user, but that path is covered by
     * {@code ClprEndpointPublicationHandlerTest} rather than here.
     */
    private static final EnumSet<HederaFunctionality> NODE_INTERNAL_CLPR_FUNCTIONS =
            EnumSet.of(HederaFunctionality.ClprEndpointPublication);

    @LeakyHapiTest(overrides = {"clpr.enabled"})
    @DisplayName("Every CLPR HAPI call returns CLPR_NOT_ENABLED at ingest when CLPR is disabled")
    final Stream<DynamicTest> allClprHapiCallsAreRejectedWhenDisabled() {
        final var crypto = new ClprChannelCrypto();
        final var txns = clprTxns(crypto);
        txns.values().forEach(op -> op.payingWith(GENESIS).hasPrecheck(CLPR_NOT_ENABLED));

        final var operations = new ArrayList<SpecOperation>();
        operations.add(withOpContext((spec, opLog) -> assertCoversEveryClprEntryPoint(txns)));
        operations.add(overriding("clpr.enabled", "false"));
        operations.addAll(txns.values());
        // Explicit payment skips automatic COST_ANSWER negotiation, testing ANSWER_ONLY directly.
        operations.add(clprGetLedgerConfiguration()
                .payingWith(GENESIS)
                .nodePayment(1L)
                .hasAnswerOnlyPrecheck(CLPR_NOT_ENABLED));
        operations.add(
                clprGetEndpointManifest().payingWith(GENESIS).nodePayment(1L).hasAnswerOnlyPrecheck(CLPR_NOT_ENABLED));
        // Both query response types must reject access while CLPR is disabled.
        operations.add(clprGetLedgerConfiguration().payingWith(GENESIS).hasCostAnswerPrecheck(CLPR_NOT_ENABLED));
        operations.add(clprGetEndpointManifest().payingWith(GENESIS).hasCostAnswerPrecheck(CLPR_NOT_ENABLED));
        return hapiTest(operations.toArray(SpecOperation[]::new));
    }

    @LeakyEmbeddedHapiTest(
            reason = MUST_SKIP_INGEST,
            overrides = {"clpr.enabled"})
    @DisplayName("Every CLPR transaction that reaches consensus fails with CLPR_NOT_ENABLED")
    final Stream<DynamicTest> allClprTransactionsAreRejectedAtConsensusWhenDisabled() {
        final var crypto = new ClprChannelCrypto();
        final var txns = clprTxns(crypto);
        // Addressing a non-default node skips ingest, so the CLPR_NOT_ENABLED below can only come
        // from AbstractClprHandler.handle — the guard the ingest test above can never reach.
        txns.values()
                .forEach(
                        op -> op.payingWith(GENESIS).setNode(INGEST_BYPASS_NODE).hasKnownStatus(CLPR_NOT_ENABLED));

        final var operations = new ArrayList<SpecOperation>();
        operations.add(overriding("clpr.enabled", "false"));
        operations.addAll(txns.values());
        return hapiTest(operations.toArray(SpecOperation[]::new));
    }

    @LeakyHapiTest(overrides = {"clpr.enabled"})
    @DisplayName("A CLPR transaction inside an atomic batch is rejected at ingest when CLPR is disabled")
    final Stream<DynamicTest> clprInsideAtomicBatchIsRejectedAtIngestWhenDisabled() {
        final var crypto = new ClprChannelCrypto();
        return hapiTest(
                overriding("clpr.enabled", "false"),
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                // AtomicBatchConfig.blacklist does not exclude CLPR, so a batch is a real way to
                // smuggle one in; IngestChecker.runAllChecks must recurse and reject it.
                atomicBatch(clprRegisterChannel()
                                .ownershipCommitment(crypto.commitment())
                                .payingWith(GENESIS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(CLPR_NOT_ENABLED));
    }

    @LeakyEmbeddedHapiTest(
            reason = MUST_SKIP_INGEST,
            overrides = {"clpr.enabled"})
    @DisplayName("A CLPR transaction inside an atomic batch fails at consensus when CLPR is disabled")
    final Stream<DynamicTest> clprInsideAtomicBatchIsRejectedAtConsensusWhenDisabled() {
        final var crypto = new ClprChannelCrypto();
        return hapiTest(
                overriding("clpr.enabled", "false"),
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                atomicBatch(clprRegisterChannel()
                                .ownershipCommitment(crypto.commitment())
                                .payingWith(GENESIS)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(CLPR_NOT_ENABLED))
                        .payingWith(BATCH_OPERATOR)
                        .setNode(INGEST_BYPASS_NODE)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @LeakyHapiTest(overrides = {"clpr.enabled"})
    @DisplayName("Every CLPR system contract halts with CLPR_NOT_ENABLED when CLPR is disabled")
    final Stream<DynamicTest> allClprSystemContractsHaltWhenDisabled() {
        final var operations = new ArrayList<SpecOperation>();
        operations.add(overriding("clpr.enabled", "false"));
        // A direct top-level call surfaces the halt reason as the record status, so this pins the
        // exact response code rather than the generic revert an intermediate contract would show.
        CLPR_SYSTEM_CONTRACT_NUMS.forEach(
                num -> operations.add(contractCallWithFunctionAbi(num, PROBE_ABI, (Object) new byte[] {1})
                        .payingWith(GENESIS)
                        .gas(GAS_TO_OFFER)
                        .refusingEthConversion()
                        .hasKnownStatus(CLPR_NOT_ENABLED)));
        return hapiTest(operations.toArray(SpecOperation[]::new));
    }

    @LeakyHapiTest(overrides = {"clpr.enabled"})
    @DisplayName("A deployed contract calling the CLPR router reverts when CLPR is disabled")
    final Stream<DynamicTest> clprRouterCalledFromContractRevertsWhenDisabled() {
        return hapiTest(
                overriding("clpr.enabled", "false"),
                uploadInitCode(CLPR_CONTRACT),
                contractCreate(CLPR_CONTRACT),
                cryptoCreate(CALLER).balance(ONE_HUNDRED_HBARS),
                // Both router entry points, not just the mutating one: a read-only method that
                // leaked channel state while CLPR is off would be just as much of a regression.
                contractCall(CLPR_CONTRACT, "sendMessage", new byte[32], new byte[32], new byte[0], new byte[0])
                        .gas(GAS_TO_OFFER)
                        .payingWith(CALLER)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                contractCall(CLPR_CONTRACT, "getChannelQueueState", (Object) new byte[32])
                        .gas(GAS_TO_OFFER)
                        .payingWith(CALLER)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED));
    }

    @LeakyHapiTest(overrides = {"clpr.enabled", "clpr.endpointManifestEnabled"})
    @DisplayName("clpr.endpointManifestEnabled cannot revive CLPR while clpr.enabled is false")
    final Stream<DynamicTest> manifestFlagCannotReviveClprWhenMasterFlagIsOff() {
        final var crypto = new ClprChannelCrypto();
        // endpointManifestEnabled gates a second, largely independent slice of CLPR (the manifest
        // reconciler, the dual-ABI verifier dispatch, bundle step 1b). Several call sites read it
        // alone; clpr.enabled must still dominate every one of them.
        return hapiTest(
                overridingTwo("clpr.enabled", "false", "clpr.endpointManifestEnabled", "true"),
                clprGetEndpointManifest().payingWith(GENESIS).hasCostAnswerPrecheck(CLPR_NOT_ENABLED),
                clprRegisterChannel()
                        .ownershipCommitment(crypto.commitment())
                        .payingWith(GENESIS)
                        .hasPrecheck(CLPR_NOT_ENABLED),
                contractCallWithFunctionAbi(CLPR_SYSTEM_CONTRACT_NUMS.getFirst(), PROBE_ABI, (Object) new byte[] {1})
                        .payingWith(GENESIS)
                        .gas(GAS_TO_OFFER)
                        .refusingEthConversion()
                        .hasKnownStatus(CLPR_NOT_ENABLED));
    }

    @LeakyHapiTest(overrides = {"clpr.enabled"})
    @DisplayName("Control: the same calls stop returning CLPR_NOT_ENABLED once the flag is on")
    final Stream<DynamicTest> clprGateIsLoadBearing() {
        final var crypto = new ClprChannelCrypto();
        // clpr.enabled defaults to false on this branch, so every assertion above would also hold
        // for a feature that was never built. Flipping the flag proves the gate — not the absence
        // of an implementation — is what produces CLPR_NOT_ENABLED.
        return hapiTest(
                overriding("clpr.enabled", "true"),
                clprRegisterChannel().ownershipCommitment(crypto.commitment()).payingWith(GENESIS),
                clprGetLedgerConfiguration().payingWith(GENESIS));
    }

    // ---- Helpers ----

    /**
     * Builds one instance of each CLPR transaction, keyed by its {@code ClprService} RPC name.
     * Bodies are populated well enough to pass pure checks and pre-handle so that malformed input
     * cannot mask a missing feature gate; the referenced channel/connector need not exist, because
     * a disabled call must stop before any entity lookup. {@code endpointNodeId(0)} is the one
     * exception — {@code ClprSubmitBundleHandler.preHandle} resolves it against the node store
     * before {@code handle} runs, and an unknown id would surface as INVALID_NODE_ID instead.
     */
    private static LinkedHashMap<String, HapiTxnOp<?>> clprTxns(final ClprChannelCrypto crypto) {
        // This reference need not exist: disabled calls must stop before entity lookup.
        final var unusedContract = ContractID.newBuilder().setContractNum(1234L).build();
        final var txns = new LinkedHashMap<String, HapiTxnOp<?>>();
        txns.put("registerChannel", clprRegisterChannel().ownershipCommitment(crypto.commitment()));
        txns.put(
                "completeChannel",
                clprCompleteChannel()
                        .channelId(crypto.channelId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.signature())
                        .verifierContractId(unusedContract)
                        .configProofBytes(new byte[] {1}));
        txns.put("closeChannel", clprCloseChannel().channelId(crypto.channelId()));
        txns.put(
                "submitBundle",
                clprSubmitBundle()
                        .channelId(crypto.channelId())
                        .bundlePayload(new byte[0])
                        .endpointNodeId(0L));
        txns.put(
                "redactMessage",
                clprRedactMessage().channelId(crypto.channelId()).messageId(1L));
        txns.put("registerConnector", clprRegisterConnector().commitment(crypto.connectorCommitment()));
        txns.put(
                "completeConnector",
                clprCompleteConnector()
                        .connectorId(crypto.connectorId())
                        .publicKey(crypto.publicKey())
                        .signature(crypto.connectorSignature())
                        .salt(crypto.connectorSalt())
                        .channelId(crypto.channelId())
                        .connectorContractId(unusedContract)
                        .adminKeyName(GENESIS)
                        .lockedStake(100_000_000L));
        txns.put(
                "deregisterConnector",
                clprDeregisterConnector()
                        .channelId(crypto.channelId())
                        .connectorId(crypto.connectorId())
                        .stakeRecipient(GENESIS));
        txns.put(
                "updateLedgerConfiguration",
                clprUpdateLedgerConfiguration()
                        .serviceAddress(new byte[] {0, 0, 1})
                        .throttles(HapiClprUpdateLedgerConfiguration.defaultThrottles()));
        return txns;
    }

    /**
     * Fails if a CLPR entry point has been added without a disabled-feature assertion, from either
     * direction: a new {@code ClprService} RPC, or a new {@code Clpr*} functionality that reaches
     * the node by some other route. Checking both matters because they can diverge — a new
     * functionality that is gated at ingest but has no RPC (as {@code ClprEndpointPublication}
     * already does) is invisible to the service descriptor alone.
     */
    private static void assertCoversEveryClprEntryPoint(final LinkedHashMap<String, HapiTxnOp<?>> txns) {
        final var assertedRpcs = new LinkedHashSet<>(txns.keySet());
        assertedRpcs.add("getLedgerConfiguration");
        assertedRpcs.add("getEndpointManifest");
        assertEquals(
                ClprServiceGrpc.getServiceDescriptor().getMethods().stream()
                        .map(MethodDescriptor::getBareMethodName)
                        .collect(toSet()),
                assertedRpcs,
                "Every ClprService RPC must have a disabled-feature assertion");

        final var expectedFunctions = EnumSet.noneOf(HederaFunctionality.class);
        for (final var function : HederaFunctionality.values()) {
            if (function.name().startsWith("Clpr")) {
                expectedFunctions.add(function);
            }
        }
        expectedFunctions.removeAll(NODE_INTERNAL_CLPR_FUNCTIONS);
        assertEquals(
                expectedFunctions.size(),
                assertedRpcs.size(),
                "Every Clpr* HederaFunctionality must have a disabled-feature assertion, or be listed "
                        + "in NODE_INTERNAL_CLPR_FUNCTIONS with the reason it is unreachable from HAPI");
    }
}
