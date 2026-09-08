// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.node.app.hapi.utils.CommonUtils.extractTransactionBody;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.selectedItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.streamMustIncludePassWithReplayFrom;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.SelectedItemsAssertion.SELECTED_ITEMS_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.GenesisSubprocessTest;
import com.hedera.services.bdd.GenesisSubprocessTest.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import java.time.Duration;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Asserts {@code LedgerIdPublication} is externalized to the record stream when TSS is enabled at
 * genesis (#25643). Needs a real multi-node subprocess network; the ceremony can't complete embedded.
 */
public class LedgerIdPublicationTest {
    private static final int NETWORK_SIZE = 4;

    @HapiTest
    @GenesisSubprocessTest(
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        applicationPropertiesOverrides = {
                            "tss.hintsEnabled", "true",
                            "tss.historyEnabled", "true",
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 1,
                        applicationPropertiesOverrides = {
                            "tss.hintsEnabled", "true",
                            "tss.historyEnabled", "true",
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 2,
                        applicationPropertiesOverrides = {
                            "tss.hintsEnabled", "true",
                            "tss.historyEnabled", "true",
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE"
                        }),
                @SubProcessNodeConfig(
                        nodeId = 3,
                        applicationPropertiesOverrides = {
                            "tss.hintsEnabled", "true",
                            "tss.historyEnabled", "true",
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE"
                        })
            })
    final Stream<DynamicTest> ledgerIdPublicationExternalizedInRecordStream() {
        return hapiTest(
                streamMustIncludePassWithReplayFrom(
                        selectedItems(ledgerIdPublicationValidator(), 1, (spec, item) -> {
                            try {
                                return extractTransactionBody(item.getTransaction())
                                        .hasLedgerIdPublication();
                            } catch (Exception e) {
                                return false;
                            }
                        }),
                        Duration.ofMinutes(5)),
                // Drive traffic so the genesis ceremony completes and externalizes the ledger id.
                cryptoCreate("a"),
                sleepForSeconds(5),
                cryptoCreate("b"),
                sleepForSeconds(5),
                cryptoCreate("c"),
                sleepForSeconds(5),
                cryptoCreate("d"),
                sleepForSeconds(5),
                cryptoCreate("e"),
                sleepForSeconds(5));
    }

    private static VisibleItemsValidator ledgerIdPublicationValidator() {
        return (spec, allItems) -> {
            final var items = Objects.requireNonNull(allItems.get(SELECTED_ITEMS_KEY));
            assertEquals(1, items.size(), "Expected exactly one LedgerIdPublication in the record stream");
            // PBJ re-parse: protobuf-java LedgerIdPublication type is in a non-exported package.
            final TransactionBody body;
            try {
                body = TransactionBody.PROTOBUF.parse(
                        Bytes.wrap(items.getFirst().body().toByteArray()));
            } catch (ParseException e) {
                throw new AssertionError("Could not parse selected transaction body", e);
            }
            assertTrue(body.hasLedgerIdPublication(), "Selected item is not a LedgerIdPublication");
            assertEquals("Ledger id", body.memo(), "Unexpected memo on ledger id publication");
            final var publication = body.ledgerIdPublicationOrThrow();
            assertTrue(publication.ledgerId().length() > 0, "Ledger id must not be empty");
            assertTrue(
                    publication.historyProofVerificationKey().length() > 0,
                    "History proof verification key must not be empty");
            assertEquals(
                    NETWORK_SIZE,
                    publication.nodeContributions().size(),
                    "Expected one node contribution per network node");
            publication.nodeContributions().forEach(contribution -> {
                assertTrue(contribution.weight() > 0, "Node contribution weight must be positive");
                assertTrue(
                        contribution.historyProofKey().length() > 0,
                        "Node contribution history proof key must not be empty");
            });
        };
    }
}
