// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hederahashgraph.api.proto.java.ExchangeRateSet;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExchangeRatesPojo {
    private RatePojo currentRate;
    private RatePojo nextRate;

    public static ExchangeRatesPojo fromProto(ExchangeRateSet proto) {
        var pojo = new ExchangeRatesPojo();
        pojo.setCurrentRate(RatePojo.fromProto(proto.getCurrentRate()));
        pojo.setNextRate(RatePojo.fromProto(proto.getNextRate()));
        return pojo;
    }

    public ExchangeRateSet toProto() {
        return ExchangeRateSet.newBuilder()
                .setCurrentRate(currentRate.toProto())
                .setNextRate(nextRate.toProto())
                .build();
    }

    public RatePojo getCurrentRate() {
        return currentRate;
    }

    public void setCurrentRate(RatePojo currentRate) {
        this.currentRate = currentRate;
    }

    public RatePojo getNextRate() {
        return nextRate;
    }

    public void setNextRate(RatePojo nextRate) {
        this.nextRate = nextRate;
    }
}
