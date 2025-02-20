// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure;

public class RegistryNotFound extends RuntimeException {
    public RegistryNotFound(String msg) {
        super(msg);
    }
}
