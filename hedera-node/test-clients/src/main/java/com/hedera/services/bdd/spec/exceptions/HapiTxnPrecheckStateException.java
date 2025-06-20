// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.exceptions;

public class HapiTxnPrecheckStateException extends IllegalStateException {
    public HapiTxnPrecheckStateException(String msg) {
        super(msg);
    }
}
