// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.systemtask.schemas.V069SystemTaskSchema.SYSTEM_TASK_QUEUE_STATE_ID;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_LAST_ASSIGNED_CONSENSUS_TIME;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.INDIRECT_ACCOUNT_SHAPE;
import static com.hedera.services.bdd.spec.keys.KeyShape.PREDEFINED_SHAPE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewAccount;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewQueue;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.exposeSpecSecondTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.node.app.systemtask.SystemTaskService;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(-1)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
public class RepeatableIndirectKeysTest {
    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("accounts.indirectKeysEnabled", "true"));
    }

    /**
     * Validates that an indirect key user's materialized key (and hence signing requirements)
     * reflect the latest key change to the account it indirectly references, once propagated.
     */
    @RepeatableHapiTest(value = {NEEDS_LAST_ASSIGNED_CONSENSUS_TIME, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> keyPropagationTaskReflectedInUserKey() {
        final var lastSecond = new AtomicLong();
        return hapiTest(
                exposeSpecSecondTo(lastSecond::set),
                newKeyNamed("firstKey"),
                newKeyNamed("replacementKey"),
                cryptoCreate("target").key("firstKey"),
                newKeyNamed("controlKey"),
                newKeyNamed("userKey")
                        .shape(threshOf(1, PREDEFINED_SHAPE, INDIRECT_ACCOUNT_SHAPE)
                                .signedWith(sigs("controlKey", "target"))),
                cryptoCreate("user").key("userKey"),
                // New account should have a materialized key
                viewAccount("user", account -> assertNotNull(account.materializedKey())),
                // Target account should have one indirect user
                sourcingContextual(spec -> viewAccount("target", account -> {
                    assertEquals(1, account.numIndirectKeyUsers());
                    final var userId = spec.registry().getAccountID("user");
                    assertEquals(toPbj(userId), account.firstKeyUserId());
                })),
                // Verify we can change user by signing with the target key
                cryptoUpdate("user").memo("Testing 123").payingWith("target").signedBy("target"),
                // Confirm the task queue is currently empty
                viewQueue(SystemTaskService.NAME, SYSTEM_TASK_QUEUE_STATE_ID, queue -> assertNull(queue.peek())),
                // Now update the target account
                cryptoUpdate("target").key("replacementKey"));
    }
}
