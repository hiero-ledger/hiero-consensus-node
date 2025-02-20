// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.verification;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public record Condition(@NonNull BooleanSupplier condition, @NonNull Supplier<String> errorMessage) {}
