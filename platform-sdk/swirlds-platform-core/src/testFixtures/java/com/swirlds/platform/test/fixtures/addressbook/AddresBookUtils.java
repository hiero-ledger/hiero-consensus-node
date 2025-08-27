// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.addressbook;

import static org.mockito.Mockito.mock;

import com.swirlds.platform.system.Platform;
import java.security.cert.X509Certificate;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.Address;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.model.roster.SerializableX509Certificate;

/**
 * Utilities for constructing AddressBook needed for tests
 */
public class AddresBookUtils {

    public static AddressBook createPretendBookFrom(final Platform platform, final boolean withKeyDetails) {
        final var cert = mock(X509Certificate.class);
        final var address1 = new Address(
                platform.getSelfId(),
                "",
                "",
                10L,
                null,
                -1,
                "123456789",
                -1,
                new SerializableX509Certificate(cert),
                new SerializableX509Certificate(cert),
                "");
        final var address2 = new Address(
                NodeId.of(1),
                "",
                "",
                10L,
                null,
                -1,
                "123456789",
                -1,
                new SerializableX509Certificate(cert),
                new SerializableX509Certificate(cert),
                "");
        return new AddressBook(List.of(address1, address2));
    }
}
