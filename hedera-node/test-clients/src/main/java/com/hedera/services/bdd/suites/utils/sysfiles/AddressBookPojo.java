// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles;

import static java.util.stream.Collectors.toList;

import com.hederahashgraph.api.proto.java.NodeAddress;
import com.hederahashgraph.api.proto.java.NodeAddressBook;
import java.util.List;
import java.util.function.Function;

public class AddressBookPojo {
    private List<BookEntryPojo> entries;

    public List<BookEntryPojo> getEntries() {
        return entries;
    }

    public void setEntries(List<BookEntryPojo> entries) {
        this.entries = entries;
    }

    public static AddressBookPojo addressBookFrom(NodeAddressBook book) {
        return from(book, BookEntryPojo::fromGrpc);
    }

    public static AddressBookPojo nodeDetailsFrom(NodeAddressBook book) {
        return from(book, BookEntryPojo::fromGrpc);
    }

    private static AddressBookPojo from(NodeAddressBook book, Function<NodeAddress, BookEntryPojo> converter) {
        var pojo = new AddressBookPojo();
        pojo.setEntries(book.getNodeAddressList().stream().map(converter).collect(toList()));
        return pojo;
    }
}
