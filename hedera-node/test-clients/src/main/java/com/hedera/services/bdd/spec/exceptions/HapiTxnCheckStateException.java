// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.exceptions;

public class HapiTxnCheckStateException extends IllegalStateException {
    public HapiTxnCheckStateException(String msg) {
        super(msg);
    }
}
