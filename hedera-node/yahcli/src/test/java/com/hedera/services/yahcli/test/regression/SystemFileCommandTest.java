// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.regression;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateLargeFile;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.yahcli.test.YahcliTestBase.REGRESSION;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.DEFAULT_WORKING_DIR;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.TEST_NETWORK;
import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.yahcliSystemFile;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.suites.utils.sysfiles.serdes.StandardSerdes;
import com.hedera.services.bdd.suites.utils.sysfiles.serdes.SysFileSerde;
import com.hedera.services.yahcli.suites.Utils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(REGRESSION)
@OrderedInIsolation
public class SystemFileCommandTest {

    @Order(0)
    @LeakyHapiTest
    final Stream<DynamicTest> readmeSystemFileUpdateExample() {
        final var fileNum = Utils.rationalized("address-book");
        final SysFileSerde<String> serde = StandardSerdes.SYS_FILE_SERDES.get(fileNum);

        final var networkSize = new AtomicLong();
        return hapiTest(
                // save original content
                getFileContents(String.valueOf(fileNum)).saveToRegistry("originalAddressBookContent"),
                // download system file (address-book.json)
                yahcliSystemFile("download", "address-book"),
                // update file
                doingContextual(spec -> editAddressBookContent()),
                // upload updated file
                yahcliSystemFile("upload", "address-book"),
                // validate the new content
                doingContextual(spec -> {
                    allRunFor(
                            spec,
                            getFileContents(String.valueOf(fileNum))
                                    .saveReadableTo(
                                            serde::fromRawFile,
                                            addressBookFilePath().toString()));
                    // Validate that addressBook entries count is equal to network size plus one
                    networkSize.set(spec.getNetworkNodes().size());
                    assertEquals(networkSize.get() + 1, addressBookEntriesCount());
                }),

                // rollback initial address book content
                doingContextual(spec -> {
                    spec.registry()
                            .saveKey(String.valueOf(fileNum), spec.registry().getKey(GENESIS));
                    allRunFor(
                            spec,
                            updateLargeFile(
                                    GENESIS,
                                    String.valueOf(fileNum),
                                    ByteString.copyFrom(spec.registry().getBytes("originalAddressBookContent"))));
                }),

                // validate initial content
                doingContextual(spec -> {
                    allRunFor(
                            spec,
                            getFileContents(String.valueOf(fileNum))
                                    .saveReadableTo(
                                            serde::fromRawFile,
                                            addressBookFilePath().toString()));
                    // Validate that addressBook entries count is equal to network size
                    networkSize.set(spec.getNetworkNodes().size());
                    assertEquals(networkSize.get(), addressBookEntriesCount());
                }));
    }

    // Helpers

    private Path addressBookFilePath() {
        return Path.of(DEFAULT_WORKING_DIR.get(), TEST_NETWORK, "sysfiles", "addressBook.json");
    }

    private void editAddressBookContent() {
        try {
            final var addressBookContent = Files.readString(addressBookFilePath());

            // Parse the address book JSON
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode addressBookNode = objectMapper.readTree(addressBookContent);

            // Create the new node entry
            ObjectNode newNode = objectMapper.createObjectNode();
            newNode.put("nodeId", 4);
            newNode.put("certHash", "<N/A>");
            newNode.put("nodeAccount", "0.0.7");

            // Create endpoints array
            ArrayNode endpoints = objectMapper.createArrayNode();

            // Endpoint
            ObjectNode endpoint = objectMapper.createObjectNode();
            endpoint.put("ipAddressV4", "127.0.0.1");
            endpoint.put("port", 50207);
            endpoints.add(endpoint);

            // Add endpoint to the node
            newNode.set("endpoints", endpoints);

            // Add the new node to the address book
            ArrayNode nodesArray = (ArrayNode) addressBookNode.get("entries");
            nodesArray.add(newNode);

            // Write the updated address book back to file
            Files.writeString(
                    addressBookFilePath(),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(addressBookNode));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Parse and count the entries in the address book
    private int addressBookEntriesCount() {
        try {
            final var addressBookContent = Files.readString(addressBookFilePath());

            // Parse the address book JSON
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode addressBookNode = objectMapper.readTree(addressBookContent);
            return ((ArrayNode) addressBookNode.get("entries")).size();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
