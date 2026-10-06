// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprDeregisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Single-network HAPI tests for the CLPR Connector lifecycle.
 *
 * <p><b>Scope after the commit-reveal API migration (spec §6.3 Connector Management).</b>
 * Per spec §6.3, connector registration is a two-phase commit-reveal handshake that mirrors
 * the channel commit-reveal scheme (§5.1.1 anti-squatting rationale):
 *
 * <ul>
 *   <li><b>Phase 1 — {@code registerConnector}</b> (§6.3): body carries only a 32-byte
 *       {@code ownership_commitment = keccak256(connector_id || public_key)}; no Connector
 *       state is created.</li>
 *   <li><b>Phase 2 — {@code completeConnector}</b> (§6.3): re-derives
 *       {@code connector_id = keccak256(channel_id || public_key || salt)}, verifies the
 *       commitment, verifies the signature over
 *       {@code keccak256(connector_id || service_address)}, checks the connector contract is
 *       a deployed contract, verifies {@code locked_stake >= clpr.minLockedStake}, then
 *       creates the Connector in active state.</li>
 * </ul>
 *
 * <p>The bulk of legacy register-time validation (source address shape, stake, contract type,
 * signatures, duplicate detection) has moved to Phase 2. End-to-end coverage of the new flow
 * is already provided by:
 *
 * <ul>
 *   <li>{@code ClprOrchestratorSubmitTest} (embedded) — drives register + complete + inbound
 *       sync against a real handler chain.</li>
 *   <li>{@code com.hedera.services.bdd.suites.interledger.ClprMessagesSuite} (multi-network)
 *       — full cross-ledger commit-reveal then real bundle flow.</li>
 * </ul>
 *
 * <p>What's kept here: the two pre-body precheck assertions that don't depend on the body
 * shape ({@code CLPR_NOT_ENABLED} on both register and deregister) and a body-shape check
 * for the new register API ({@code commitment.length() == 32}).
 */
@Tag(CLPR)
public class ClprConnectorSuite {

    private static final String CONNECTOR_CONTRACT = "GlobalProperties";

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsRegisterWhenDisabled() {
        return hapiTest(
                overriding("clpr.enabled", "false"),
                newKeyNamed("adminKey"),
                uploadInitCode(CONNECTOR_CONTRACT),
                contractCreate(CONNECTOR_CONTRACT),
                clprRegisterConnector()
                        .commitment(new byte[32])
                        .payingWith(GENESIS)
                        .hasPrecheck(CLPR_NOT_ENABLED));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsRegisterWithMalformedCommitment() {
        // Spec §6.3: registerConnector body carries a 32-byte ownership_commitment.
        // ClprRegisterConnectorHandler.pureChecks enforces the length invariant.
        return hapiTest(
                overriding("clpr.enabled", "true"),
                clprRegisterConnector()
                        .commitment(new byte[16]) // wrong length
                        .payingWith(GENESIS)
                        .hasPrecheck(INVALID_TRANSACTION_BODY));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> rejectsDeregisterWhenDisabled() {
        return hapiTest(
                overriding("clpr.enabled", "false"),
                clprDeregisterConnector()
                        .channelId(new byte[32])
                        .connectorId(new byte[32])
                        .stakeRecipient(GENESIS)
                        .payingWith(GENESIS)
                        .hasPrecheck(CLPR_NOT_ENABLED));
    }
}
