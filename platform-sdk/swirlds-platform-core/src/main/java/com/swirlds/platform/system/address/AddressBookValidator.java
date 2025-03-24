// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.address;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * This class provides methods for validating new address books. These methods do not throw exceptions if validation
 * fails, but they are intentionally very noisy in the logs when they fail.
 */
public final class AddressBookValidator {

    private static final Logger logger = LogManager.getLogger(AddressBookValidator.class);

    private AddressBookValidator() {}

    // FUTURE WORK: this class is only partially completed. Additional validation will be added before changing address
    // books at runtime is fully supported.

    /**
     * Make sure the address book has at least some weight.
     *
     * @param addressBook the address book to validate
     * @return if the address book passes this validation
     */
    public static boolean hasNonZeroWeight(final AddressBook addressBook) {
        if (addressBook.getTotalWeight() <= 0) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "address book for round {} has {} total weight",
                    addressBook.getRound(),
                    addressBook.getTotalWeight());
            return false;
        }
        return true;
    }

    /**
     * Make sure the address book is not empty.
     *
     * @param addressBook the address book to validate
     * @return if the address book passes this validation
     */
    public static boolean isNonEmpty(final AddressBook addressBook) {
        if (addressBook.getSize() == 0) {
            logger.error(EXCEPTION.getMarker(), "address book for round {} is empty", addressBook.getRound());
            return false;
        }
        return true;
    }

    /**
     * Validates that the next address book is not empty.
     *
     * The value of `nextNodeId` is no longer used in validation.
     *
     * @param oldAddressBook the old address book
     * @param newAddressBook the new address book
     * @throws IllegalStateException if the new address book is empty.
     */
    public static void validateNewAddressBook(
            @NonNull final AddressBook oldAddressBook, @NonNull final AddressBook newAddressBook) {
        final int newSize = newAddressBook.getSize();

        if (newSize == 0) {
            throw new IllegalStateException("The new address book's size must be greater than 0");
        }
    }

    /**
     * Check if a genesis address book is valid. In the case of an invalid address book, this method will write an error
     * message to the log.
     *
     * @param candidateAddressBook a candidate address book that is being tested for validity
     * @return true if the address book is valid
     */
    public static boolean isGenesisAddressBookValid(final AddressBook candidateAddressBook) {

        return hasNonZeroWeight(candidateAddressBook) && isNonEmpty(candidateAddressBook);
    }

    /**
     * Check if a new address book transition is valid. In the case of an invalid address book, this method will write
     * an error message to the log.
     *
     * @param candidateAddressBook the new address book that follows the current address book
     * @return true if the transition is valid
     */
    public static boolean isNextAddressBookValid(final AddressBook candidateAddressBook) {

        return hasNonZeroWeight(candidateAddressBook) && isNonEmpty(candidateAddressBook);
    }

    /**
     * Checks that the addresses between the two address books are identical except for weight value.
     *
     * @param addressBook1 An address book to compare for equality.
     * @param addressBook2 An address book to compare for equality.
     * @return true of the two address books contain the same addresses except for weight values, false otherwise.
     */
    public static boolean sameExceptForWeight(
            @NonNull final AddressBook addressBook1, @NonNull final AddressBook addressBook2) {
        Objects.requireNonNull(addressBook1, "addressBook1 must not be null");
        Objects.requireNonNull(addressBook2, "addressBook2 must not be null");
        final int addressBookSize = addressBook1.getSize();
        if (addressBookSize != addressBook2.getSize()) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Address books have different sizes. Address book 1 has size {}, address book 2 has size {}.",
                    addressBookSize,
                    addressBook2.getSize());
            return false;
        }
        return IntStream.range(0, addressBookSize)
                .mapToObj(i -> {
                    final NodeId nodeId1 = addressBook1.getNodeId(i);
                    final NodeId nodeId2 = addressBook2.getNodeId(i);
                    final Address address1 = addressBook1.getAddress(nodeId1);
                    final Address address2 = addressBook2.getAddress(nodeId2);
                    if (address1 == null || address2 == null) {
                        logger.error(EXCEPTION.getMarker(), "Address at index {} is null when accessed in order.", i);
                        throw new IllegalStateException("Address at index " + i + " is null.");
                    }
                    final boolean equal = address1.equalsWithoutWeight(address2);
                    if (!equal) {
                        logger.error(
                                EXCEPTION.getMarker(),
                                "Address at position {} is not the same between the two address books.",
                                i);
                    }
                    return equal;
                })
                .reduce((left, right) -> left && right)
                .orElse(false);
    }
}
