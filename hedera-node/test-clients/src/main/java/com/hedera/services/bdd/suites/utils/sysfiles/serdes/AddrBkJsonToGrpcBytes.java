// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles.serdes;

import static com.hedera.services.bdd.suites.utils.sysfiles.AddressBookPojo.addressBookFrom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.bdd.suites.utils.sysfiles.AddressBookPojo;
import com.hedera.services.bdd.suites.utils.sysfiles.BookEntryPojo;
import com.hederahashgraph.api.proto.java.NodeAddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;

public class AddrBkJsonToGrpcBytes implements SysFileSerde<String> {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String fromRawFile(byte[] bytes) {
        try {
            var pojoBook = addressBookFrom(NodeAddressBook.parseFrom(bytes));
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(pojoBook);
        } catch (InvalidProtocolBufferException | JsonProcessingException e) {
            throw new IllegalArgumentException("Not an address book!", e);
        }
    }

    @Override
    public byte[] toRawFile(String styledFile, @Nullable String interpolatedSrcDir) {
        return grpcBytesFromPojo(pojoFrom(styledFile), interpolatedSrcDir);
    }

    @Override
    public byte[] toValidatedRawFile(String styledFile, @Nullable String interpolatedSrcDir) {
        var pojo = pojoFrom(styledFile);
        for (var entry : pojo.getEntries()) {
            for (BookEntryPojo.EndpointPojo endpointPojo : entry.getEndpoints()) {
                validateIPandPort(endpointPojo.getIpAddressV4(), endpointPojo.getPort());
            }
        }
        return grpcBytesFromPojo(pojo, interpolatedSrcDir);
    }

    private void validateIPandPort(String IpV4Address, Integer portNum) {
        try {
            BookEntryPojo.asOctets(IpV4Address);
        } catch (Exception e) {
            throw new IllegalStateException("Endpoint IP field cannot be set to '" + IpV4Address + "'", e);
        }

        try {
            if (portNum <= 0) {
                throw new IllegalStateException("Endpoint port field cannot be set to '" + portNum + "'");
            }
        } catch (NullPointerException e) {
            throw new IllegalStateException("Endpoint port field is not set", e);
        }
    }

    private AddressBookPojo pojoFrom(String styledFile) {
        try {
            return mapper.readValue(styledFile, AddressBookPojo.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Not an address book!", ex);
        }
    }

    private byte[] grpcBytesFromPojo(
            @NonNull final AddressBookPojo pojoBook, @Nullable final String interpolatedSrcDir) {
        NodeAddressBook.Builder addressBookForClients = NodeAddressBook.newBuilder();
        pojoBook.getEntries().stream()
                .flatMap(pojo -> pojo.toGrpcStream(interpolatedSrcDir))
                .forEach(addressBookForClients::addNodeAddress);
        return addressBookForClients.build().toByteArray();
    }

    @Override
    public String preferredFileName() {
        return "addressBook.json";
    }
}
