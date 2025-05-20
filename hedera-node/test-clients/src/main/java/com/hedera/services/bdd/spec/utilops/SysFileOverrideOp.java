// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateLargeFile;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.utils.sysfiles.serdes.StandardSerdes.SYS_FILE_SERDES;
import static java.util.Objects.requireNonNull;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.OptionalLong;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A utility operation to override the contents of a dynamic system file, remembering the initial contents for
 * later restoration.
 */
public class SysFileOverrideOp extends UtilOp {
    private static final Logger log = LogManager.getLogger(SysFileOverrideOp.class);

    /**
     * Defines a target system file to override by its default number (which we never override in test).
     */
    public enum Target {
        FEES(111L),
        EXCHANGE_RATES(112L),
        THROTTLES(123L),
        PERMISSIONS(122L);

        Target(final long number) {
            this.number = number;
        }

        /**
         * Returns the number of the system file targeted by this enum constant.
         * @return the number of the system file
         */
        public long number() {
            return number;
        }

        private final long number;
    }

    private final Target target;
    private final Supplier<String> overrideSupplier;

    private boolean autoRestoring = true;
    private byte[] originalContents;

    /**
     * Creates a new operation to override the contents of the given system file on the target network of the given,
     * but <b>not</b> automatically restore the original contents when the spec that submitted this operation completes.
     * @param target the system file to override
     * @param overrideSupplier the supplier of the new contents of the system file
     * @return the new operation
     */
    public static SysFileOverrideOp withoutAutoRestoring(
            @NonNull final Target target, @NonNull final Supplier<String> overrideSupplier) {
        final var op = new SysFileOverrideOp(target, overrideSupplier);
        op.autoRestoring = false;
        return op;
    }

    public SysFileOverrideOp(@NonNull final Target target, @NonNull final Supplier<String> overrideSupplier) {
        this.target = requireNonNull(target);
        this.overrideSupplier = requireNonNull(overrideSupplier);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var fileNumber = String.valueOf(target.number());
        allRunFor(spec, getFileContents(fileNumber).consumedBy(bytes -> this.originalContents = bytes));
        log.info("Took snapshot of {}", target);
        final var styledContents = overrideSupplier.get();
        // The supplier can return null to indicate that this operation should not update the file,
        // as the parent spec wants to override the contents itself later
        if (styledContents != null) {
            final var rawContents = SYS_FILE_SERDES.get(target.number()).toRawFile(styledContents, null);
            log.info("Now automatically overriding {}", target);
            allRunFor(
                    spec,
                    updateLargeFile(
                            GENESIS, fileNumber, ByteString.copyFrom(rawContents), true, OptionalLong.of(ONE_HBAR)));
            if (target == Target.FEES) {
                if (!spec.tryReinitializingFees()) {
                    log.warn("Failed to reinitialize fees");
                }
            }
        }
        return false;
    }

    /**
     * Restores the original contents of the targeted system file on the target network of the given spec.
     *
     * @param spec the spec to restore the original contents on
     */
    public void restoreContentsIfNeeded(@NonNull final HapiSpec spec) {
        requireNonNull(spec);
        if (originalContents != null) {
            final var fileNumber = String.valueOf(target.number());

            allRunFor(
                    spec,
                    updateLargeFile(
                            GENESIS,
                            fileNumber,
                            ByteString.copyFrom(originalContents),
                            true,
                            OptionalLong.of(ONE_HBAR)));
            log.info("Restored {} to snapshot", target);
        }
    }

    /**
     * Indicates whether the original contents of the targeted system file should be automatically restored when
     * the spec that submitted this operation completes.
     * @return {@code true} if the original contents should be restored automatically, otherwise {@code false}
     */
    public boolean isAutoRestoring() {
        return autoRestoring;
    }
}
