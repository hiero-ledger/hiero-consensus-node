// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.exceptions;

public class HapiQueryPrecheckStateException extends IllegalStateException {
    public HapiQueryPrecheckStateException(String msg) {
        super(msg);
    }
}
