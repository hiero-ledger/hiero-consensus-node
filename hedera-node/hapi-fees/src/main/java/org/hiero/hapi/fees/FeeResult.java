// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import java.util.LinkedHashMap;
import java.util.Map;

public class FeeResult {
    public long service = 0;
    public long node = 0;
    public long network = 0;
    public Map<String, FeeDetail> details = new LinkedHashMap<>();

    public void addServiceFee(String label, long value, long cost) {
        details.put(label, new FeeDetail(value, cost));
        service += cost;
    }

    public void addNodeFee(String label, long value, long cost) {
        details.put(label, new FeeDetail(value, cost));
        node += cost;
    }

    public void addNetworkFee(String label, long value, long cost) {
        details.put(label, new FeeDetail(value, cost));
        network += cost;
    }

    public long total() {
        return this.node + this.network + this.service;
    }

    public static class FeeDetail {
        public long value;
        public long fee;

        public FeeDetail(long value, long fee) {
            this.value = value;
            this.fee = fee;
        }

        @Override
        public String toString() {
            return "FeeDetail{" + this.value + ", " + this.fee + "}";
        }
    }

    @Override
    public String toString() {
        return "FeeResult{" + "fee=" + service + ", details=" + details + '}';
    }
}
