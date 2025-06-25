// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.getDeduction;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.readableTransferList;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.TinyBarTransfers;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TransferList;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Assertions;

public class TransferListAsserts extends BaseErroringAssertsProvider<TransferList> {
    public static TransferListAsserts exactParticipants(Function<HapiSpec, List<AccountID>> provider) {
        return new ExactParticipantsAssert(provider);
    }

    // non-standard initialization
    @SuppressWarnings("java:S3599")
    public static TransferListAsserts noCreditAboveNumber(ToLongFunction<HapiSpec> provider) {
        return new TransferListAsserts() {
            {
                registerProvider((spec, o) -> {
                    TransferList actual = (TransferList) o;
                    long maxAllowed = provider.applyAsLong(spec);
                    Assertions.assertTrue(
                            actual.getAccountAmountsList().stream()
                                    .filter(aa -> aa.getAmount() > 0)
                                    .allMatch(aa -> aa.getAccountID().getAccountNum() <= maxAllowed),
                            "Transfers include a credit above account 0.0." + maxAllowed);
                });
            }
        };
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public static TransferListAsserts including(Function<HapiSpec, TransferList>... providers) {
        return new ExplicitTransferAsserts(Arrays.asList(providers));
    }

    public static TransferListAsserts includingDeduction(LongSupplier from, long amount) {
        return new DeductionAsserts(from, amount);
    }

    public static TransferListAsserts includingDeduction(String from, long amount) {
        return new SpecificDeductionAsserts(from, amount);
    }

    public static TransferListAsserts includingDeduction(String desc, String payer) {
        return new QualifyingDeductionAssert(desc, payer);
    }

    public static Function<HapiSpec, Map.Entry<AccountID, Long>> to(String account, Long amount) {
        return spec -> new AbstractMap.SimpleEntry<>(spec.registry().getAccountID(account), amount);
    }

    public static Function<HapiSpec, Map.Entry<AccountID, Long>> from(String account, Long amount) {
        return spec -> new AbstractMap.SimpleEntry<>(spec.registry().getAccountID(account), -1 * amount);
    }

    protected void assertInclusion(TransferList of, TransferList in) {
        if (!new TinyBarTransfers(of).test(in)) {
            Assertions.assertEquals(TxnUtils.printable(of), TxnUtils.printable(in), "Transfers missing from list!");
        }
    }
}

class ExactParticipantsAssert extends TransferListAsserts {
    public ExactParticipantsAssert(Function<HapiSpec, List<AccountID>> provider) {
        registerProvider((spec, o) -> {
            List<AccountID> expectedParticipants = provider.apply(spec);
            TransferList actual = (TransferList) o;
            Assertions.assertEquals(
                    expectedParticipants.size(), actual.getAccountAmountsCount(), "Wrong number of participants!");
            for (int i = 0, n = expectedParticipants.size(); i < n; i++) {
                Assertions.assertEquals(
                        expectedParticipants.get(i), actual.getAccountAmounts(i).getAccountID());
            }
        });
    }
}

class ExplicitTransferAsserts extends TransferListAsserts {
    public ExplicitTransferAsserts(List<Function<HapiSpec, TransferList>> providers) {
        providers.forEach(provider -> registerProvider((spec, o) -> {
            TransferList expected = provider.apply(spec);
            assertInclusion(expected, (TransferList) o);
        }));
    }
}

class QualifyingDeductionAssert extends TransferListAsserts {
    public QualifyingDeductionAssert(String desc, String payer) {
        registerProvider((spec, o) -> {
            var transfers = (TransferList) o;
            var hasQualifying = getDeduction(transfers, asId(payer, spec)).isPresent();
            if (!hasQualifying) {
                Assertions.fail("No qualifying " + desc + " from " + payer + " in " + readableTransferList(transfers));
            }
        });
    }
}

class DeductionAsserts extends TransferListAsserts {
    public DeductionAsserts(LongSupplier from, long amount) {
        registerProvider((spec, o) -> {
            TransferList transfers = (TransferList) o;
            long num = from.getAsLong();
            Assertions.assertTrue(
                    transfers.getAccountAmountsList().stream()
                            .anyMatch(aa -> aa.getAmount() == -amount
                                    && aa.getAccountID().getAccountNum() == num),
                    String.format("No deduction of -%d tinyBars from 0.0.%d detected!", amount, num));
        });
    }
}

class SpecificDeductionAsserts extends TransferListAsserts {
    public SpecificDeductionAsserts(String account, long amount) {
        registerProvider((spec, o) -> {
            TransferList transfers = (TransferList) o;
            AccountID payer = asId(account, spec);
            Assertions.assertTrue(
                    transfers.getAccountAmountsList().stream()
                            .anyMatch(aa -> aa.getAmount() == -amount
                                    && aa.getAccountID().equals(payer)),
                    String.format("No deduction of -%d tinyBars from %s detected!", amount, account));
        });
    }
}
