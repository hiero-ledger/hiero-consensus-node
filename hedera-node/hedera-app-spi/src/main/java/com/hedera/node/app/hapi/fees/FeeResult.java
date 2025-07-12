package com.hedera.node.app.hapi.fees;

import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public class FeeResult {
    public double fee = 0.0;
    public Map<String, FeeDetail> details = new LinkedHashMap<>();

    public void addDetail(String label, int value, double cost) {
        details.put(label, new FeeDetail(value, cost));
        fee = Math.round((fee + cost) * 1_000_000.0) / 1_000_000.0;
    }

    public static class FeeDetail {
        public int value;
        public double fee;

        public FeeDetail(int value, double fee) {
            this.value = value;
            this.fee = fee;
        }
        @Override
        public String toString() {
            return "FeeDetail{" + this.value + ", " + new DecimalFormat("#.000 000").format(this.fee) + "}";
        }
    }

    @Override
    public String toString() {
        return "FeeResult{" +
                "fee=" + String.format("%.6f", fee) +
                ", details=" + details +
                '}';
    }
}
