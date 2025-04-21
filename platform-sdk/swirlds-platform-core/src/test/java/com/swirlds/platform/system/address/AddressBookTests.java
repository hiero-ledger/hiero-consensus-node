// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.address;

import static com.swirlds.platform.system.address.AddressBookUtils.parseAddressBookText;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBookBuilder;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBuilder;
import com.swirlds.platform.test.fixtures.crypto.PreGeneratedX509Certs;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.text.ParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AddressBook Tests")
class AddressBookTests {

    /**
     * Make sure that an address book is internally self consistent.
     */
    private void validateAddressBookConsistency(final AddressBook addressBook) {

        final int size = addressBook.getSize();

        long totalWeight = 0;
        int numberWithWeight = 0;
        final Set<NodeId> nodeIds = new HashSet<>();

        NodeId previousId = null;
        int expectedIndex = 0;
        for (final Address address : addressBook) {
            if (previousId != null) {
                assertTrue(address.getNodeId().compareTo(previousId) > 0, "iteration is not in proper order");
            }
            previousId = address.getNodeId();

            assertEquals(expectedIndex, addressBook.getIndexOfNodeId(address.getNodeId()), "invalid index");
            assertEquals(address.getNodeId(), addressBook.getNodeId(expectedIndex), "wrong ID returned for index");
            expectedIndex++;

            assertEquals(
                    address.getNodeId(),
                    addressBook.getNodeId(address.getNickname()),
                    "wrong ID returned for public key");

            assertSame(address, addressBook.getAddress(address.getNodeId()), "invalid address returned");

            nodeIds.add(address.getNodeId());
            totalWeight += address.getWeight();
            if (address.getWeight() != 0) {
                numberWithWeight++;
            }
        }

        assertEquals(size, nodeIds.size(), "size metric is incorrect");
        assertEquals(totalWeight, addressBook.getTotalWeight(), "incorrect total weight");
        assertEquals(numberWithWeight, addressBook.getNumberWithWeight(), "incorrect number with weight");

        if (!addressBook.isEmpty()) {
            final Address lastAddress = addressBook.getAddress(addressBook.getNodeId(addressBook.getSize() - 1));
            assertTrue(
                    lastAddress.getNodeId().compareTo(addressBook.getNextAvailableNodeId()) < 0,
                    "incorrect next node ID");
        } else {
            assertEquals(0, size, "address book expected to be empty");
        }

        // Check size using an alternate strategy
        final AtomicInteger alternateSize = new AtomicInteger(0);
        addressBook.getNodeIdSet().forEach(nodeId -> {
            assertTrue(addressBook.contains(nodeId), "node ID not found");
            alternateSize.incrementAndGet();
        });
        assertEquals(size, alternateSize.get(), "size is incorrect");
    }

    /**
     * Validate that the address book contains the expected values.
     */
    private void validateAddressBookContents(
            final AddressBook addressBook, final Map<NodeId, Address> expectedAddresses) {

        assertEquals(expectedAddresses.size(), addressBook.getSize(), "unexpected number of addresses");

        for (final NodeId nodeId : expectedAddresses.keySet()) {
            assertTrue(addressBook.contains(nodeId), "address book does not have address for node");
            assertEquals(expectedAddresses.get(nodeId), addressBook.getAddress(nodeId), "address should match");
        }
    }

    @Test
    @DisplayName("Address Book Update Weight Test")
    void validateAddressBookUpdateWeightTest() {
        final RandomAddressBookBuilder generator =
                RandomAddressBookBuilder.create(getRandomPrintSeed()).withSize(10);
        final AddressBook addressBook = generator.build();
        final Address address = addressBook.getAddress(addressBook.getNodeId(0));
        final long totalWeight = addressBook.getTotalWeight();
        final long newWeight = address.getWeight() + 1;

        addressBook.updateWeight(address.getNodeId(), newWeight);

        final Address updatedAddress = addressBook.getAddress(addressBook.getNodeId(0));
        assertEquals(newWeight, updatedAddress.getWeight(), "weight should be updated");
        assertEquals(totalWeight + 1, addressBook.getTotalWeight(), "total weight should be updated by 1");
        final Address reverted = updatedAddress.copySetWeight(newWeight - 1);
        assertEquals(address, reverted, "reverted address should be equal to original");
        assertThrows(
                IllegalArgumentException.class,
                () -> addressBook.updateWeight(address.getNodeId(), -1),
                "should not be able to set negative weight");
        assertThrows(
                NoSuchElementException.class,
                () -> addressBook.updateWeight(addressBook.getNextAvailableNodeId(), 1),
                "should not be able to set weight for non-existent node");
    }

    /**
     * Build an address to be added to an address book.
     *
     * @param random      the random number generator to use
     * @param addressBook the address book to add the address to
     * @return a new address
     */
    @NonNull
    private static Address buildNextAddress(@NonNull final Random random, @NonNull final AddressBook addressBook) {
        return RandomAddressBuilder.create(random)
                .withNodeId(NodeId.of(addressBook.getNextAvailableNodeId().id() + random.nextInt(0, 3)))
                .build();
    }

    @Test
    @DisplayName("Add/Remove Test")
    void addRemoveTest() {
        final Random random = getRandomPrintSeed();

        final RandomAddressBookBuilder generator = RandomAddressBookBuilder.create(random)
                .withAverageWeight(100)
                .withWeightStandardDeviation(50)
                .withSize(100);

        final AddressBook addressBook = generator.build();
        final Map<NodeId, Address> expectedAddresses = new HashMap<>();
        addressBook.iterator().forEachRemaining(address -> expectedAddresses.put(address.getNodeId(), address));

        final int operationCount = 1_000;
        for (int i = 0; i < operationCount; i++) {
            if (random.nextBoolean() && addressBook.getSize() > 0) {
                final int indexToRemove = random.nextInt(addressBook.getSize());
                final NodeId nodeIdToRemove = addressBook.getNodeId(indexToRemove);
                assertNotNull(expectedAddresses.remove(nodeIdToRemove), "item to be removed should be present");
                addressBook.remove(nodeIdToRemove);
            } else {
                final Address newAddress = buildNextAddress(random, addressBook);
                expectedAddresses.put(newAddress.getNodeId(), newAddress);
                addressBook.add(newAddress);
            }

            validateAddressBookConsistency(addressBook);
            validateAddressBookContents(addressBook, expectedAddresses);
        }
    }

    @Test
    @DisplayName("Update Test")
    void updateTest() {
        final Random random = getRandomPrintSeed();

        final RandomAddressBookBuilder generator =
                RandomAddressBookBuilder.create(random).withSize(100);

        final AddressBook addressBook = generator.build();
        final Map<NodeId, Address> expectedAddresses = new HashMap<>();
        addressBook.iterator().forEachRemaining(address -> expectedAddresses.put(address.getNodeId(), address));

        final int operationCount = 1_000;
        for (int i = 0; i < operationCount; i++) {
            final int indexToUpdate = random.nextInt(addressBook.getSize());
            final NodeId nodeIdToUpdate = addressBook.getNodeId(indexToUpdate);

            final Address updatedAddress = buildNextAddress(random, addressBook).copySetNodeId(nodeIdToUpdate);

            expectedAddresses.put(nodeIdToUpdate, updatedAddress);
            addressBook.add(updatedAddress);

            validateAddressBookConsistency(addressBook);
            validateAddressBookContents(addressBook, expectedAddresses);
        }
    }

    @Test
    @DisplayName("Get/Set Round Test")
    void getSetRoundTest() {
        final Randotron randotron = Randotron.create();
        final AddressBook addressBook =
                RandomAddressBookBuilder.create(randotron).build();

        addressBook.setRound(1234);
        assertEquals(1234, addressBook.getRound(), "unexpected round");
    }

    @Test
    @DisplayName("Equality Test")
    void equalityTest() {
        final Randotron randotron = Randotron.create();

        final AddressBook addressBook1 =
                RandomAddressBookBuilder.create(randotron).withSize(100).build();
        final AddressBook addressBook2 = RandomAddressBookBuilder.create(randotron.copyAndReset())
                .withSize(100)
                .build();

        assertEquals(addressBook1, addressBook2, "address books should be the same");
        assertEquals(addressBook1.hashCode(), addressBook2.hashCode(), "address books should have the same hash code");

        final Address updatedAddress = addressBook1
                .getAddress(addressBook1.getNodeId(randotron.nextInt(100)))
                .copySetNickname("foobar");
        addressBook1.add(updatedAddress);

        assertNotEquals(addressBook1, addressBook2, "address books should not be the same");
    }

    @Test
    @DisplayName("toString() Test")
    void atoStringSanityTest() {
        final AddressBook addressBook =
                RandomAddressBookBuilder.create(getRandomPrintSeed()).build();

        // Basic sanity check, make sure this doesn't throw an exception
        System.out.println(addressBook);
    }

    @Test
    @DisplayName("Serialization Test")
    void serializationTest() throws IOException, ConstructableRegistryException {
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds");

        final AddressBook original = RandomAddressBookBuilder.create(getRandomPrintSeed())
                .withSize(100)
                .build();

        // FQDN Support: addresses must support long text based host names.
        original.add(original.getAddress(original.getNodeId(0))
                .copySetHostnameInternal(
                        "this.is.a.really.long.host.name.that.should.be.able.to.fit.in.the.address.book"));

        // make sure that certs are part of the round trip test.
        assertNotNull(original.getAddress(NodeId.of(0)).getSigCert());
        assertNotNull(original.getAddress(NodeId.of(0)).getAgreeCert());

        validateAddressBookConsistency(original);

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final SerializableDataOutputStream out = new SerializableDataOutputStream(byteOut);

        out.writeSerializable(original, true);

        final SerializableDataInputStream in =
                new SerializableDataInputStream(new ByteArrayInputStream(byteOut.toByteArray()));

        final AddressBook deserialized = in.readSerializable();
        validateAddressBookConsistency(deserialized);

        assertEquals(original, deserialized);
    }

    @Test
    @DisplayName("clear() test")
    void clearTest() {
        final AddressBook addressBook = RandomAddressBookBuilder.create(getRandomPrintSeed())
                .withSize(100)
                .withMaximumWeight(10)
                .withAverageWeight(5)
                .withWeightStandardDeviation(5)
                .build();

        validateAddressBookConsistency(addressBook);

        addressBook.clear();

        assertEquals(0, addressBook.getTotalWeight(), "there should be no weight");
        assertEquals(0, addressBook.getNumberWithWeight(), "there should be no nodes with any weight");
        assertEquals(0, addressBook.getSize());

        validateAddressBookConsistency(addressBook);
    }

    @Test
    @DisplayName("Reinsertion Test")
    void reinsertionTest() {
        final Randotron randotron = Randotron.create();

        final AddressBook addressBook = new AddressBook();

        for (int i = 0; i < 100; i++) {
            addressBook.add(buildNextAddress(randotron, addressBook));
        }

        validateAddressBookConsistency(addressBook);

        final Address addressToRemove = addressBook.getAddress(addressBook.getNodeId(50));
        addressBook.remove(addressBook.getNodeId(50));
        assertThrows(
                IllegalArgumentException.class,
                () -> addressBook.add(addressToRemove),
                "should not be able to insert an address once it has been removed");
    }

    @Test
    @DisplayName("Out Of Order add() Test")
    void outOfOrderAddTest() {
        final Randotron randotron = Randotron.create();

        final RandomAddressBookBuilder generator =
                RandomAddressBookBuilder.create(randotron).withSize(100);
        final AddressBook addressBook = generator.build();

        // The address book has gaps. Make sure we can't insert anything into those gaps.
        for (int i = 0; i < addressBook.getNextAvailableNodeId().id(); i++) {

            final Address address = buildNextAddress(randotron, addressBook).copySetNodeId(NodeId.of(i));

            if (addressBook.contains(NodeId.of(i))) {
                // It's ok to update an existing address
                addressBook.add(address);
            } else {
                // We can't add something into a gap
                assertThrows(
                        IllegalArgumentException.class,
                        () -> addressBook.add(address),
                        "shouldn't be able to add this address");
            }
        }

        validateAddressBookConsistency(addressBook);
    }

    @Test
    @DisplayName("Max Size Test")
    void maxSizeTest() {
        final Randotron randotron = Randotron.create();

        final AddressBook addressBook = new AddressBook();

        for (int i = 0; i < AddressBook.MAX_ADDRESSES; i++) {
            addressBook.add(buildNextAddress(randotron, addressBook));
        }

        validateAddressBookConsistency(addressBook);

        assertThrows(
                IllegalStateException.class,
                () -> addressBook.add(buildNextAddress(randotron, addressBook)),
                "shouldn't be able to exceed max address book size");
    }

    @Test
    @DisplayName("Roundtrip address book serialization and deserialization compatible with config.txt")
    void roundTripSerializeAndDeserializeCompatibleWithConfigTxt() throws ParseException {
        final RandomAddressBookBuilder generator = RandomAddressBookBuilder.create(getRandomPrintSeed());
        final AddressBook addressBook = generator.build();
        // FQDN Support: modify address in address book to have a text based host name.
        addressBook.add(addressBook
                .getAddress(addressBook.getNodeId(0))
                .copySetHostnameInternal("localhost")
                .copySetHostnameExternal("localhost"));

        // make one of the memo fields an empty string
        final NodeId firstNode = addressBook.getNodeId(0);
        addressBook.add(addressBook.getAddress(firstNode).copySetMemo(""));
        final NodeId secondNode = addressBook.getNodeId(1);
        addressBook.add(addressBook.getAddress(secondNode).copySetMemo("has a memo"));

        final String addressBookText = addressBook.toConfigText();
        final AddressBook parsedAddressBook = parseAddressBookText(addressBookText);
        // Equality done on toConfigText() strings since the randomly generated address book has public key data.
        assertEquals(addressBookText, parsedAddressBook.toConfigText(), "The AddressBooks are not equal.");
        assertTrue(parsedAddressBook.getAddress(firstNode).getMemo().isEmpty(), "memo is empty");
        assertEquals(parsedAddressBook.getAddress(secondNode).getMemo(), "has a memo", "memo matches");

        for (int i = 0; i < addressBook.getSize(); i++) {
            final Address address = addressBook.getAddress(addressBook.getNodeId(i));
            final Address parsedAddress = parsedAddressBook.getAddress(parsedAddressBook.getNodeId(i));
            assertEquals(address.getNodeId(), parsedAddress.getNodeId(), "node id matches");
            // these are the 8 fields of the config.txt address book.
            assertEquals(address.getSelfName(), parsedAddress.getSelfName(), "self name matches");
            assertEquals(address.getNickname(), parsedAddress.getNickname(), "nickname matches");
            assertEquals(address.getWeight(), parsedAddress.getWeight(), "weight matches");
            assertEquals(
                    address.getHostnameInternal(), parsedAddress.getHostnameInternal(), "internal hostname matches");
            assertEquals(address.getPortInternal(), parsedAddress.getPortInternal(), "internal port matches");
            assertEquals(
                    address.getHostnameExternal(), parsedAddress.getHostnameExternal(), "external hostname matches");
            assertEquals(address.getPortExternal(), parsedAddress.getPortExternal(), "external port matches");
            assertEquals(address.getMemo(), parsedAddress.getMemo(), "memo matches");
        }
    }

    @Test
    @DisplayName("Testing exceptions in parsing address books used in config.txt")
    void parseExceptionTestsInParsingAddressBookConfigText() {
        // not enough parts to make an address line
        validateParseException("address", 1);
        validateParseException("address, 0", 2);
        validateParseException("address, 1, nickname", 3);
        validateParseException("address, 2, nickname, selfname", 4);
        validateParseException("address, 3, nickname, selfname, 10", 5);
        validateParseException("address, 4, nickname, selfname, 10, 192.168.0.1", 6);
        validateParseException("address, 5, nickname, selfname, 10, 192.168.0.1, 5000", 7);
        validateParseException("address, 6, nickname, selfname, 10, 192.168.0.1, 5000, 8.8.8.8", 8);

        // Too many parts
        validateParseException("address, 7, nickname, selfname, 10, 192.168.0.1, 5000, 8.8.8.8, 5000, memo, extra", 11);

        // bad parsing of parts.
        validateParseException("not an address, 8, nickname, selfname, 10, 192.168.0.1, 5000, 8.8.8.8, 5000", 0);
        validateParseException("address, 9, nickname, selfname, not a weight, 192.168.0.1, 5000, 8.8.8.8, 5000", 4);
        validateParseException("address, 10, nickname, selfname, 10, 192.168.0.1, not a port, 8.8.8.8, 5000", 6);
        validateParseException("address, 11, nickname, selfname, 10, 192.168.0.1, 5000, 8.8.8.8, not a port", 8);
    }

    private void validateParseException(final String addressBook, final int part) {
        assertThrows(ParseException.class, () -> parseAddressBookText(addressBook));
        try {
            parseAddressBookText(addressBook);
        } catch (final ParseException e) {
            assertEquals(part, e.getErrorOffset(), "The part number is wrong in the exception: " + e.getMessage());
        }
    }

    @Test
    void testMalformedAndMissingCertificate() {
        final Address base = new Address();
        // establish baseline behavior.
        final X509Certificate goodCert = PreGeneratedX509Certs.getSigCert(0).getCertificate();
        final Address withCert = base.copySetSigCert(goodCert);
        assertEquals(goodCert, withCert.getSigCert());
        assertEquals(goodCert.getPublicKey(), withCert.getSigCert().getPublicKey());

        // test null certificate
        final Address nullCert = base.copySetSigCert(null);
        assertNull(nullCert.getSigCert());

        // test malformed certificate
        final Address badCert = base.copySetSigCert(PreGeneratedX509Certs.createBadCertificate());
        assertNull(badCert.getSigCert());
    }
}
