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
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

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
}
