// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlock;

import com.hedera.hapi.block.stream.Block;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;

/**
 * A {@link UtilOp} that validates the streams produced by the target network of the given {@link HapiSpec} with dynamic
 * validators.
 */
public class BlockStreamValidationOp extends UtilOp implements LifecycleTest {
    private static final Logger log = LogManager.getLogger(BlockStreamValidationOp.class);

    private final List<BlockStreamValidator> blockValidators = new ArrayList<>();

    public BlockStreamValidationOp withBlockValidation(Consumer<List<Block>> validation) {
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
        if (!blockValidators.isEmpty()) {
            List<Block> blocks = StreamValidationOp.readMaybeBlockStreamsFor(spec)
                    .orElseGet(() -> Assertions.fail("No block streams found"));
            blockValidators.forEach(v -> v.validateBlocks(blocks));
        }
        return false;
    }
}
