package com.hedera.node.app.hapi.fees;

public class FeeCheckResult {
    public boolean result;
    public String message;

    public static FeeCheckResult success() {
        FeeCheckResult r = new FeeCheckResult();
        r.result = true;
        return r;
    }

    public static FeeCheckResult failure(String msg) {
        FeeCheckResult r = new FeeCheckResult();
        r.result = false;
        r.message = msg;
        return r;
    }
}
