// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles;

import static java.util.stream.Collectors.toList;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hederahashgraph.api.proto.java.NodeAddress;
import com.hederahashgraph.api.proto.java.NodeAddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
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

    public static AddressBookPojo nodeDetailsFrom(@NonNull final byte[] bytes) {
        try {
            final var book = NodeAddressBook.parseFrom(bytes);
            return nodeDetailsFrom(book);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static AddressBookPojo from(NodeAddressBook book, Function<NodeAddress, BookEntryPojo> converter) {
        var pojo = new AddressBookPojo();
        pojo.setEntries(book.getNodeAddressList().stream().map(converter).collect(toList()));
        return pojo;
    }
}
