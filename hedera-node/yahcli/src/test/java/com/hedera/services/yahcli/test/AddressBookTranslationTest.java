// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.suites.utils.sysfiles.AddressBookPojo;
import com.hederahashgraph.api.proto.java.NodeAddressBook;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class AddressBookTranslationTest {
    @Test
    void noIpv4AddressParsesAsPojo() {
        final var builder = NodeAddressBook.newBuilder();
        builder.addNodeAddressBuilder()
                .addServiceEndpointBuilder()
                .setDomainName("www.example.com")
                .setPort(1234)
                .setIpAddressV4(ByteString.EMPTY)
                .build();
        final var newAddressBook = builder.build();
        final var result = AddressBookPojo.addressBookFrom(newAddressBook);
        Assertions.assertThat(result.getEntries()).hasSizeGreaterThan(0);
    }
}
