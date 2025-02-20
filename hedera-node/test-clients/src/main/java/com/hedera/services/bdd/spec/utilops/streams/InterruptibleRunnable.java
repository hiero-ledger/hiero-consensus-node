// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

public interface InterruptibleRunnable {
    void run() throws InterruptedException;
}
