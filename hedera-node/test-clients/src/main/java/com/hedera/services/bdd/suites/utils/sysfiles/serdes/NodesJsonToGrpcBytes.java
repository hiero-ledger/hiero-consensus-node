// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles.serdes;

import static com.hedera.services.bdd.suites.utils.sysfiles.AddressBookPojo.nodeDetailsFrom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.bdd.suites.utils.sysfiles.AddressBookPojo;
import com.hederahashgraph.api.proto.java.NodeAddressBook;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;

public class NodesJsonToGrpcBytes implements SysFileSerde<String> {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String fromRawFile(byte[] bytes) {
        try {
            var pojoBook = nodeDetailsFrom(NodeAddressBook.parseFrom(bytes));
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(pojoBook);
        } catch (InvalidProtocolBufferException | JsonProcessingException e) {
            throw new IllegalArgumentException("Not a node details file!", e);
        }
    }

    @Override
    public byte[] toRawFile(String styledFile, @Nullable String interpolatedSrcDir) {
        try {
            var pojoBook = mapper.readValue(styledFile, AddressBookPojo.class);
            NodeAddressBook.Builder addressBook = NodeAddressBook.newBuilder();
            pojoBook.getEntries().stream()
                    .flatMap(pojo -> pojo.toGrpcStream(interpolatedSrcDir))
                    .forEach(addressBook::addNodeAddress);
            return addressBook.build().toByteArray();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Not a Node Details file!", ex);
        }
    }

    @Override
    public String preferredFileName() {
        return "nodeDetails.json";
    }
}
