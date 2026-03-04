// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1137;

import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.registeredNodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.registeredNodeDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.registeredNodeUpdate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hederahashgraph.api.proto.java.RegisteredServiceEndpoint;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
@Tag(INTEGRATION)
public class RegisteredNodeCrudTest {
    private static final String RN_ADMIN_KEY = "rnAdminKey";
    private static final String RN_NEW_ADMIN_KEY = "rnNewAdminKey";
    private static final String CREATE_TXN = "registeredNodeCreate";

    private static final List<RegisteredServiceEndpoint> DEFAULT_ENDPOINTS =
            List.of(RegisteredServiceEndpoint.newBuilder()
                    .setDomainName("blocknode.example.com")
                    .setPort(8080)
                    .setBlockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                            .setEndpointApi(RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS)
                            .build())
                    .build());

    @HapiTest
    @DisplayName("create → update (admin key rotation) → delete")
    final Stream<DynamicTest> crudHappyPath() {
        final var createdId = new AtomicLong();
        return hapiTest(
                newKeyNamed(RN_ADMIN_KEY),
                newKeyNamed(RN_NEW_ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(RN_ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .via(CREATE_TXN)
                        .exposingCreatedIdTo(createdId::set)
                        .hasKnownStatus(SUCCESS),
                getTxnRecord(CREATE_TXN).logged().hasPriority(recordWith().hasNonZeroRegisteredNodeId()),
                withOpContext((spec, opLog) -> {
                    final var update = registeredNodeUpdate(createdId::get)
                            .description("new-desc")
                            .adminKey(spec.registry().getKey(RN_NEW_ADMIN_KEY))
                            .signedBy(DEFAULT_PAYER, RN_ADMIN_KEY, RN_NEW_ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    final var delete = registeredNodeDelete(createdId::get)
                            .signedBy(DEFAULT_PAYER, RN_NEW_ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, update, delete);
                }));
    }

    @HapiTest
    @DisplayName("description-only update preserves endpoints and admin key")
    final Stream<DynamicTest> descriptionOnlyUpdate() {
        final var createdId = new AtomicLong();
        return hapiTest(
                newKeyNamed(RN_ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(RN_ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .exposingCreatedIdTo(createdId::set)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    final var update = registeredNodeUpdate(createdId::get)
                            .description("updated-desc")
                            .signedBy(DEFAULT_PAYER, RN_ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    final var cleanup = registeredNodeDelete(createdId::get)
                            .signedBy(DEFAULT_PAYER, RN_ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, update, cleanup);
                }));
    }

    @HapiTest
    @DisplayName("endpoints replace replaces entire list")
    final Stream<DynamicTest> endpointsReplaceUpdate() {
        final var createdId = new AtomicLong();
        final var replacementEndpoints = List.of(RegisteredServiceEndpoint.newBuilder()
                .setIpAddress(ByteString.copyFrom(new byte[] {10, 0, 0, 1}))
                .setPort(9090)
                .setBlockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                        .setEndpointApi(RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.PUBLISH)
                        .build())
                .build());
        return hapiTest(
                newKeyNamed(RN_ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(RN_ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .exposingCreatedIdTo(createdId::set)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    final var update = registeredNodeUpdate(createdId::get)
                            .serviceEndpoints(replacementEndpoints)
                            .signedBy(DEFAULT_PAYER, RN_ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    final var cleanup = registeredNodeDelete(createdId::get)
                            .signedBy(DEFAULT_PAYER, RN_ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, update, cleanup);
                }));
    }

    @HapiTest
    @DisplayName("admin key rotation fails without new key signature, succeeds with both")
    final Stream<DynamicTest> adminKeyRotationRequiresDualSignature() {
        final var createdId = new AtomicLong();
        return hapiTest(
                newKeyNamed(RN_ADMIN_KEY),
                newKeyNamed(RN_NEW_ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(RN_ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .exposingCreatedIdTo(createdId::set)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    // Attempt rotation signed only by old key — should fail
                    final var failedUpdate = registeredNodeUpdate(createdId::get)
                            .adminKey(spec.registry().getKey(RN_NEW_ADMIN_KEY))
                            .signedBy(DEFAULT_PAYER, RN_ADMIN_KEY)
                            .hasKnownStatus(INVALID_SIGNATURE);
                    // Rotation signed by both old and new keys — should succeed
                    final var successfulUpdate = registeredNodeUpdate(createdId::get)
                            .adminKey(spec.registry().getKey(RN_NEW_ADMIN_KEY))
                            .signedBy(DEFAULT_PAYER, RN_ADMIN_KEY, RN_NEW_ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    final var cleanup = registeredNodeDelete(createdId::get)
                            .signedBy(DEFAULT_PAYER, RN_NEW_ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, failedUpdate, successfulUpdate, cleanup);
                }));
    }

    @HapiTest
    @DisplayName("update of non-existent registered node fails with INVALID_NODE_ID")
    final Stream<DynamicTest> updateNonExistentNodeFails() {
        return hapiTest(newKeyNamed(RN_ADMIN_KEY), withOpContext((spec, opLog) -> {
            final var update = registeredNodeUpdate(() -> 999_999L)
                    .description("ghost")
                    .signedBy(DEFAULT_PAYER)
                    .hasPrecheck(INVALID_NODE_ID);
            allRunFor(spec, update);
        }));
    }

    @HapiTest
    @DisplayName("update without admin key signature fails with INVALID_SIGNATURE")
    final Stream<DynamicTest> updateWithoutAdminKeySigFails() {
        final var createdId = new AtomicLong();
        return hapiTest(
                newKeyNamed(RN_ADMIN_KEY),
                registeredNodeCreate("rn")
                        .adminKey(RN_ADMIN_KEY)
                        .serviceEndpoints(DEFAULT_ENDPOINTS)
                        .exposingCreatedIdTo(createdId::set)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    // Attempt update signed only by payer, without admin key
                    final var update = registeredNodeUpdate(createdId::get)
                            .description("unauthorized")
                            .signedBy(DEFAULT_PAYER)
                            .hasKnownStatus(INVALID_SIGNATURE);
                    // Clean up — need admin key to delete
                    final var cleanup = registeredNodeDelete(createdId::get)
                            .signedBy(DEFAULT_PAYER, RN_ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, update, cleanup);
                }));
    }
}
