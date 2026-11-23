// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The key setters shared by the builders of the token create and update bodies. The PBJ builders share no interface,
 * so each is wrapped by an implementation of this one, which lets {@link TokenKeyUtils#addTokenKeys} be written once.
 */
public interface TokenKeyBuilder {
    void adminKey(@NonNull Key key);

    void kycKey(@NonNull Key key);

    void freezeKey(@NonNull Key key);

    void wipeKey(@NonNull Key key);

    void supplyKey(@NonNull Key key);

    void feeScheduleKey(@NonNull Key key);

    void pauseKey(@NonNull Key key);

    void metadataKey(@NonNull Key key);

    /**
     * Wraps the builder of a token update body.
     *
     * @param builder the builder to set the keys on
     * @return the wrapper
     */
    static TokenKeyBuilder of(@NonNull final TokenUpdateTransactionBody.Builder builder) {
        return new ForUpdate(builder);
    }

    /**
     * Wraps the builder of a token create body.
     *
     * @param builder the builder to set the keys on
     * @return the wrapper
     */
    static TokenKeyBuilder of(@NonNull final TokenCreateTransactionBody.Builder builder) {
        return new ForCreate(builder);
    }

    /**
     * Sets the keys on a {@link TokenUpdateTransactionBody.Builder}.
     *
     * @param builder the wrapped builder
     */
    record ForUpdate(@NonNull TokenUpdateTransactionBody.Builder builder) implements TokenKeyBuilder {
        @Override
        public void adminKey(@NonNull final Key key) {
            builder.adminKey(key);
        }

        @Override
        public void kycKey(@NonNull final Key key) {
            builder.kycKey(key);
        }

        @Override
        public void freezeKey(@NonNull final Key key) {
            builder.freezeKey(key);
        }

        @Override
        public void wipeKey(@NonNull final Key key) {
            builder.wipeKey(key);
        }

        @Override
        public void supplyKey(@NonNull final Key key) {
            builder.supplyKey(key);
        }

        @Override
        public void feeScheduleKey(@NonNull final Key key) {
            builder.feeScheduleKey(key);
        }

        @Override
        public void pauseKey(@NonNull final Key key) {
            builder.pauseKey(key);
        }

        @Override
        public void metadataKey(@NonNull final Key key) {
            builder.metadataKey(key);
        }
    }

    /**
     * Sets the keys on a {@link TokenCreateTransactionBody.Builder}.
     *
     * @param builder the wrapped builder
     */
    record ForCreate(@NonNull TokenCreateTransactionBody.Builder builder) implements TokenKeyBuilder {
        @Override
        public void adminKey(@NonNull final Key key) {
            builder.adminKey(key);
        }

        @Override
        public void kycKey(@NonNull final Key key) {
            builder.kycKey(key);
        }

        @Override
        public void freezeKey(@NonNull final Key key) {
            builder.freezeKey(key);
        }

        @Override
        public void wipeKey(@NonNull final Key key) {
            builder.wipeKey(key);
        }

        @Override
        public void supplyKey(@NonNull final Key key) {
            builder.supplyKey(key);
        }

        @Override
        public void feeScheduleKey(@NonNull final Key key) {
            builder.feeScheduleKey(key);
        }

        @Override
        public void pauseKey(@NonNull final Key key) {
            builder.pauseKey(key);
        }

        @Override
        public void metadataKey(@NonNull final Key key) {
            builder.metadataKey(key);
        }
    }
}
