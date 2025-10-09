// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.TestTags.UPGRADE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.exceptNodeIds;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.nodeIdsFrom;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateCandidateRoster;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.utils.sysfiles.AddressBookPojo.nodeDetailsFrom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.suites.utils.sysfiles.AddressBookPojo;
import com.hedera.services.bdd.suites.utils.sysfiles.BookEntryPojo;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.NodeAddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(UPGRADE)
@Order(Integer.MAX_VALUE - 4)
@HapiTestLifecycle
@OrderedInIsolation
public class CandidateRosterAfterUpdateTest implements LifecycleTest {

    private static final List<String> NODE_ACCOUNT_IDS = List.of("3", "4", "5", "6");

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("nodes.updateAccountIdAllowed", "true"));
    }

    @HapiTest
    @Order(0)
    final Stream<DynamicTest> addressBookAndNodeDetailsPopulated() {
        final var file101 = "101";
        final var file102 = "102";

        return hapiTest(withOpContext((spec, opLog) -> {
            var getFile101 = QueryVerbs.getFileContents(file101).consumedBy(bytes -> {
                AddressBookPojo addressBook;
                try {
                    addressBook = AddressBookPojo.addressBookFrom(NodeAddressBook.parseFrom(bytes));
                } catch (InvalidProtocolBufferException e) {
                    fail("Failed to parse address book", e);
                    throw new IllegalStateException("Needed for compilation; should never happen");
                }
                verifyAddressInfo(addressBook, spec);
            });
            var getFile102 = QueryVerbs.getFileContents(file102).consumedBy(bytes -> {
                final AddressBookPojo pojoBook;
                try {
                    pojoBook = nodeDetailsFrom(NodeAddressBook.parseFrom(bytes));
                } catch (InvalidProtocolBufferException e) {
                    fail("Failed to parse node details", e);
                    throw new IllegalStateException("Needed for compilation; should never happen");
                }

                verifyAddressInfo(pojoBook, spec);
            });
            allRunFor(spec, getFile101, getFile102);
        }));
    }

    @HapiTest
    @Order(1)
    final Stream<DynamicTest> validateCandidateRosterAfterAccountUpdate() {
        return hapiTest(
                cryptoCreate("poorMe").balance(0L),
                nodeUpdate("1")
                        .fullAccountId(AccountID.newBuilder()
                                .setShardNum(0)
                                .setRealmNum(0)
                                .setAccountNum(0)
                                .build()),
                nodeUpdate("3").accountId("poorMe").payingWith(DEFAULT_PAYER).signedByPayerAnd("poorMe"),
                // do fake upgrade to trigger candidate roster refresh
                prepareFakeUpgrade(),
                // validate node2 and node4 are excluded from the candidate roster
                validateCandidateRoster(exceptNodeIds(1L, 3L), addressBook -> assertThat(nodeIdsFrom(addressBook))
                        .contains(1L, 3L)));
    }

    private static void verifyAddressInfo(final AddressBookPojo addressBook, HapiSpec spec) {
        final var entries = addressBook.getEntries().stream()
                .map(BookEntryPojo::getNodeAccount)
                .toList();
        assertThat(entries).hasSizeGreaterThanOrEqualTo(NODE_ACCOUNT_IDS.size());
        var nodes = NODE_ACCOUNT_IDS.stream()
                .map(id -> String.format("%d.%d.%s", spec.shard(), spec.realm(), id))
                .toList();
        entries.forEach(nodeId -> assertThat(nodes).contains(nodeId));
    }
}
