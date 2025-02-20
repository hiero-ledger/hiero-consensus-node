// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.TestTags.NOT_REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(NOT_REPEATABLE)
public class PerpetualTransfers {
    private final AtomicLong duration = new AtomicLong(30);
    private final AtomicReference<TimeUnit> unit = new AtomicReference<>(SECONDS);
    private final AtomicInteger maxOpsPerSec = new AtomicInteger(500);

    @HapiTest
    final Stream<DynamicTest> canTransferBackAndForthForever() {
        return hapiTest(runWithProvider(transfersFactory())
                .lasting(duration::get, unit::get)
                .maxOpsPerSec(maxOpsPerSec::get));
    }

    private Function<HapiSpec, OpProvider> transfersFactory() {
        AtomicBoolean fromAtoB = new AtomicBoolean(true);
        AtomicInteger transfersSoFar = new AtomicInteger(0);
        return spec -> new OpProvider() {
            @Override
            public List<SpecOperation> suggestedInitializers() {
                return List.of(cryptoCreate("A"), cryptoCreate("B"));
            }

            @Override
            public Optional<HapiSpecOperation> get() {
                var from = fromAtoB.get() ? "A" : "B";
                var to = fromAtoB.get() ? "B" : "A";
                fromAtoB.set(!fromAtoB.get());

                var op = cryptoTransfer(tinyBarsFromTo(from, to, 1))
                        .noLogging()
                        .memo("transfer #" + transfersSoFar.getAndIncrement())
                        .deferStatusResolution();

                return Optional.of(op);
            }
        };
    }
}
