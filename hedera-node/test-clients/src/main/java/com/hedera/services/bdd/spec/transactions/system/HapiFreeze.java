// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.system;

import static com.hederahashgraph.api.proto.java.FreezeType.UNKNOWN_FREEZE_TYPE;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.Freeze;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.time.temporal.ChronoUnit.SECONDS;
import static org.hiero.base.utility.CommonUtils.hex;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.FreezeTransactionBody;
import com.hederahashgraph.api.proto.java.FreezeType;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Consumer;

public class HapiFreeze extends HapiTxnOp<HapiFreeze> {
    private boolean useRejectedStartHr = false;
    private boolean useRejectedStartMin = false;
    private boolean useRejectedEndHr = false;
    private boolean useRejectedEndMin = false;

    private int delay = 5;
    private boolean needsFreezeTimeCalc = false;
    private ChronoUnit delayUnit = SECONDS;

    private String updateFile = null;
    private Optional<Instant> freezeStartTime = Optional.empty();
    private Optional<byte[]> fileHash = Optional.empty();

    private FreezeType action;

    public HapiFreeze() {
        this(UNKNOWN_FREEZE_TYPE);
    }

    public HapiFreeze(FreezeType action) {
        this.action = action;
    }

    @Override
    protected HapiFreeze self() {
        return this;
    }

    public HapiFreeze startingAt(final Instant startTime) {
        freezeStartTime = Optional.of(startTime);
        return this;
    }

    public HapiFreeze startingIn(final int units) {
        needsFreezeTimeCalc = true;
        delay = units;
        return this;
    }

    public HapiFreeze seconds() {
        this.delayUnit = SECONDS;
        return this;
    }

    public HapiFreeze minutes() {
        this.delayUnit = MINUTES;
        return this;
    }

    public HapiFreeze withUpdateFile(String updateFile) {
        this.updateFile = updateFile;
        return this;
    }

    public HapiFreeze havingHash(byte[] data) {
        fileHash = Optional.of(data);
        return this;
    }

    public HapiFreeze withRejectedStartHr() {
        this.useRejectedStartHr = true;
        return this;
    }

    public HapiFreeze withRejectedStartMin() {
        this.useRejectedStartMin = true;
        return this;
    }

    public HapiFreeze withRejectedEndHr() {
        this.useRejectedEndHr = true;
        return this;
    }

    public HapiFreeze withRejectedEndMin() {
        this.useRejectedEndMin = true;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return Freeze;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final var opBody = spec.txns()
                .<FreezeTransactionBody, FreezeTransactionBody.Builder>body(FreezeTransactionBody.class, b -> {
                    b.setFreezeType(action);
                    if (needsFreezeTimeCalc) {
                        freezeStartTime = Optional.of(spec.consensusTime().plus(delay, delayUnit));
                    }
                    freezeStartTime.map(TxnUtils::asTimestamp).ifPresent(b::setStartTime);
                    if (updateFile != null) {
                        final var updateId = TxnUtils.asFileId(updateFile, spec);
                        b.setUpdateFile(updateId);
                    }
                    fileHash.ifPresent(h -> b.setFileHash(ByteString.copyFrom(h)));

                    if (useRejectedStartHr) {
                        b.setStartHour(1);
                    }
                    if (useRejectedStartMin) {
                        b.setStartMin(2);
                    }
                    if (useRejectedEndHr) {
                        b.setEndHour(3);
                    }
                    if (useRejectedEndMin) {
                        b.setEndMin(4);
                    }
                });
        return b -> b.setFreeze(opBody);
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) {
        return spec.fees().maxFeeTinyBars();
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        MoreObjects.ToStringHelper helper = super.toStringHelper();
        helper.add("action", action);
        if (needsFreezeTimeCalc) {
            helper.add("starting in", delay + " " + delayUnit);
        } else {
            freezeStartTime.ifPresent(instant -> helper.add("startTime", instant));
        }
        helper.add("updateFile", updateFile);
        fileHash.ifPresent(h -> helper.add("hash", hex(h)));
        return helper.omitNullValues();
    }
}
