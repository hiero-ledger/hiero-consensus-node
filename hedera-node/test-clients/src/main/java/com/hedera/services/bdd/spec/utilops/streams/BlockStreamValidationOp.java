// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import com.hedera.hapi.block.stream.Block;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.junit.support.RecordStreamValidator;
import com.hedera.services.bdd.junit.support.StreamFileAccess;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hedera.services.stream.proto.RecordStreamFile;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;

import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlock;
import static java.util.stream.Collectors.joining;

/**
 * A {@link UtilOp} that validates the streams produced by the target network of the given {@link HapiSpec} with dynamic
 * validators.
 */
public class BlockStreamValidationOp extends UtilOp implements LifecycleTest {
    private static final Logger log = LogManager.getLogger(BlockStreamValidationOp.class);

    private final List<RecordStreamValidator> recordValidators = new ArrayList<>();
    private final List<BlockStreamValidator> blockValidators = new ArrayList<>();

    public BlockStreamValidationOp withRecordValidation(
            Consumer<List<RecordStreamFile>> validation) {
        this.recordValidators.add(new RecordStreamValidator() {
            @Override
            public void validateFiles(List<RecordStreamFile> files) {
                validation.accept(files);
            }
        });
        return this;
    }

    public BlockStreamValidationOp withBlockValidation(
            Consumer<List<Block>> validation) {
        this.blockValidators.add(new BlockStreamValidator() {
            @Override
            public void validateBlocks(@NotNull List<Block> blocks) {
                validation.accept(blocks);
            }
        });
        return this;
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        // wait for record/block files will be created
        allRunFor(spec, waitUntilNextBlock().withBackgroundTraffic(true));
        // Validate the record streams
        if (!recordValidators.isEmpty()) {
            StreamFileAccess.RecordStreamData record = StreamValidationOp.readMaybeRecordStreamDataFor(spec)
                    .orElseGet(() -> Assertions.fail("No record stream found"));
            final var recordErrors = recordValidators.stream()
                    .flatMap(v -> v.validationErrorsIn(record))
                    .peek(t -> log.error("Record stream validation error", t))
                    .map(Throwable::getMessage)
                    .collect(joining(StreamValidationOp.ERROR_PREFIX));
            if (!recordErrors.isBlank()) {
                throw new AssertionError(
                        "Record stream validation failed:" + StreamValidationOp.ERROR_PREFIX + recordErrors);
            }
        }
        if (!blockValidators.isEmpty()) {
            List<Block> blocks = StreamValidationOp.readMaybeBlockStreamsFor(spec)
                    .orElseGet(() -> Assertions.fail("No block streams found"));
            final var blockErrors = blockValidators.stream()
                    .flatMap(v -> {
                        try {
                            v.validateBlocks(blocks);
                        } catch (final Throwable t) {
                            return Stream.of(t);
                        }
                        return Stream.empty();
                    })
                    .peek(t -> log.error("Block stream validation error", t))
                    .map(Throwable::getMessage)
                    .collect(joining(StreamValidationOp.ERROR_PREFIX));
            if (!blockErrors.isBlank()) {
                throw new AssertionError(
                        "Block stream validation failed:" + StreamValidationOp.ERROR_PREFIX + blockErrors);
            }
        }
        return false;
    }
}
