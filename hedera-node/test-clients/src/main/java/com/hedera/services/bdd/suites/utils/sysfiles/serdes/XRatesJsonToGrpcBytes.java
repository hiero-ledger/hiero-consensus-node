// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles.serdes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.bdd.suites.utils.sysfiles.ExchangeRatesPojo;
import com.hederahashgraph.api.proto.java.ExchangeRateSet;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;

public class XRatesJsonToGrpcBytes implements SysFileSerde<String> {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String fromRawFile(byte[] bytes) {
        try {
            var pojoRates = ExchangeRatesPojo.fromProto(ExchangeRateSet.parseFrom(bytes));
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(pojoRates);
        } catch (InvalidProtocolBufferException | JsonProcessingException e) {
            throw new IllegalArgumentException("Not an exchange rates set!", e);
        }
    }

    @Override
    public byte[] toRawFile(String styledFile, @Nullable String interpolatedSrcDir) {
        try {
            var pojoRates = mapper.readValue(styledFile, ExchangeRatesPojo.class);
            return pojoRates.toProto().toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Not an exchange rates set!", e);
        }
    }

    @Override
    public String preferredFileName() {
        return "exchangeRates.json";
    }
}
