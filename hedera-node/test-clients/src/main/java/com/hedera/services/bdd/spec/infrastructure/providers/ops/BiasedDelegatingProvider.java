// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops;

import static java.util.Collections.binarySearch;
import static java.util.stream.Collectors.toList;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link OpProvider} that delegates to a list of other providers, each with a given probability
 * of being chosen. That probability is determined by a "bias" for each provider, which has no
 * absolute meaning but is relative to the other biases.
 *
 * <p>For example, suppose we have three providers, A, B, and C, with biases 1, 2, and 3. Then the
 * probability of choosing A is 1/6, B is 1/3, and C is 1/2.
 */
public class BiasedDelegatingProvider implements OpProvider {
    private static final Logger log = LogManager.getLogger(BiasedDelegatingProvider.class);

    @SuppressWarnings("java:S2245") // using java.util.Random in tests is fine
    private final Random r = new Random(505397L);

    private final List<Integer> cumulativeBias = new ArrayList<>(List.of(0));
    private final List<OpProvider> delegates = new ArrayList<>();
    private final List<SpecOperation> globalInitializers = new ArrayList<>();
    private final List<Supplier<HapiSpecOperation[]>> summaries = new ArrayList<>();

    private boolean shouldAlwaysDefer = true;
    private boolean shouldLogNormalFlow = false;

    public BiasedDelegatingProvider withOp(OpProvider delegate, int bias) {
        if (bias != 0) {
            int n = delegates.size();
            cumulativeBias.add(cumulativeBias.get(n) + bias);
            delegates.add(delegate);
        }
        return this;
    }

    public BiasedDelegatingProvider withInitialization(SpecOperation... ops) {
        globalInitializers.addAll(List.of(ops));
        return this;
    }

    public BiasedDelegatingProvider withSummary(Supplier<HapiSpecOperation[]> summary) {
        summaries.add(summary);
        return this;
    }

    public BiasedDelegatingProvider shouldAlwaysDefer(boolean flag) {
        shouldAlwaysDefer = flag;
        return this;
    }

    public BiasedDelegatingProvider shouldLogNormalFlow(boolean flag) {
        shouldLogNormalFlow = flag;
        return this;
    }

    @Override
    public List<SpecOperation> suggestedInitializers() {
        return Stream.concat(
                        globalInitializers.stream(),
                        delegates.stream().flatMap(d -> d.suggestedInitializers().stream()))
                .collect(toList());
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        if (delegates.isEmpty()) {
            return Optional.empty();
        } else {
            while (true) {
                Optional<HapiSpecOperation> op =
                        delegates.get(randomSelection()).get();
                if (op.isPresent()) {
                    op.ifPresent(this::configureDefaults);
                    return op;
                }
            }
        }
    }

    private int randomSelection() {
        // A little LC trick to choose from our list of "n" providers with the
        // requested biases; we imagine a list of n+1 _cumulative_ biases (i.e.,
        // where the i-th number in the list is the sum of all biases from
        // providers 0 to i) and then randomly pick a number k in the range
        // [0, totalBias). The insertion point of k in the list of biases (as
        // returned by binarySearch) is the index of each provider with
        // probability proportional to its bias. We leave the proof to the reader.
        int bias = r.nextInt(cumulativeBias.get(cumulativeBias.size() - 1));
        int selection = binarySearch(cumulativeBias, bias);
        if (selection < 0) {
            selection = -1 * selection - 2;
        }
        return selection;
    }

    private void configureDefaults(HapiSpecOperation op) {
        boolean isTxnOp = isTxnOp(op);

        if (shouldAlwaysDefer && isTxnOp) {
            ((HapiTxnOp) op).deferStatusResolution();
        }

        // if we have not set the payer when we created the operation e.g. `RandomAccountDeletion`, we should default to
        // `UNIQUE_PAYER_ACCOUNT`
        // for the cases when we have set the payer e.g. `RandomOperationSignedBy`, do not overwrite it
        if (op.getPayer().isEmpty()) {
            if (isTxnOp) {
                ((HapiTxnOp) op).payingWith(UNIQUE_PAYER_ACCOUNT).fee(TRANSACTION_FEE);
            } else if (isQueryOp(op)) {
                ((HapiQueryOp) op).payingWith(UNIQUE_PAYER_ACCOUNT);
            }
        }

        if (!shouldLogNormalFlow) {
            if (isTxnOp) {
                ((HapiTxnOp) op).noLogging();
            } else if (isQueryOp(op)) {
                ((HapiQueryOp) op).noLogging();
            }
        }
    }

    boolean isTxnOp(HapiSpecOperation op) {
        return (op instanceof HapiTxnOp);
    }

    boolean isQueryOp(HapiSpecOperation op) {
        return (op instanceof HapiQueryOp);
    }
}
