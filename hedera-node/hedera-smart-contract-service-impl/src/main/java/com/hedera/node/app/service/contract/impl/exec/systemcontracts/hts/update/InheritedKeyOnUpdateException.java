// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update;

/**
 * Thrown while decoding a token update when a token key asks to inherit the caller's account key.
 * The inherited account key is only resolved when creating a token, so an update cannot honor it;
 * the update translators turn this into a {@link RejectedUpdateCall} instead of silently dropping the key.
 */
public class InheritedKeyOnUpdateException extends RuntimeException {
    public InheritedKeyOnUpdateException() {
        super("inheritAccountKey is not supported when updating token keys");
    }
}
