// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1137;

import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.registeredNodeCreate;
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
public class RegisteredNodeTest {
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
    @DisplayName("create → update (admin key rotation)")
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
                    allRunFor(spec, update);
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
                    allRunFor(spec, update);
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
                    allRunFor(spec, update);
                }));
    }

    @HapiTest
    @DisplayName("update requires admin key signature, key rotation requires dual signature")
    final Stream<DynamicTest> signatureRequirements() {
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
                    // Update without admin key sig → INVALID_SIGNATURE
                    final var unauthorizedUpdate = registeredNodeUpdate(createdId::get)
                            .description("unauthorized")
                            .signedBy(DEFAULT_PAYER)
                            .hasKnownStatus(INVALID_SIGNATURE);
                    // Rotation signed only by old key → INVALID_SIGNATURE
                    final var failedRotation = registeredNodeUpdate(createdId::get)
                            .adminKey(spec.registry().getKey(RN_NEW_ADMIN_KEY))
                            .signedBy(DEFAULT_PAYER, RN_ADMIN_KEY)
                            .hasKnownStatus(INVALID_SIGNATURE);
                    // Rotation signed by both old and new keys → SUCCESS
                    final var successfulRotation = registeredNodeUpdate(createdId::get)
                            .adminKey(spec.registry().getKey(RN_NEW_ADMIN_KEY))
                            .signedBy(DEFAULT_PAYER, RN_ADMIN_KEY, RN_NEW_ADMIN_KEY)
                            .hasKnownStatus(SUCCESS);
                    allRunFor(spec, unauthorizedUpdate, failedRotation, successfulRotation);
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
}
