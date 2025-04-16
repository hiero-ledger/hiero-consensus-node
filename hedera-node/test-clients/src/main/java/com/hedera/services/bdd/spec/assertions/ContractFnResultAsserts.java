// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import static com.hedera.services.bdd.spec.dsl.entities.SpecContract.VARIANT_NONE;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.contracts.TokenRedirectContract;
import com.hedera.services.bdd.spec.queries.contract.HapiGetContractInfo;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hedera.services.bdd.suites.utils.contracts.ContractCallResult;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ContractLoginfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.Assertions;

public class ContractFnResultAsserts extends BaseErroringAssertsProvider<ContractFunctionResult> {
    static final Logger log = LogManager.getLogger(ContractFnResultAsserts.class);

    @SuppressWarnings("java:S2245") // using java.util.Random in tests is fine
    private static final Random rand = new Random(704833L);

    public static ContractFnResultAsserts resultWith() {
        return new ContractFnResultAsserts();
    }

    /**
     * Returns a provider that asserts the result of a call to the given function of the given token redirect contract
     * is the literal result provided.
     *
     * @param redirectContract the contract to call
     * @param function the function to call
     * @param values the expected result
     * @return the provider
     */
    public static ContractFnResultAsserts redirectCallResult(
            @NonNull final TokenRedirectContract redirectContract,
            @NonNull final String function,
            @NonNull final Object... values) {
        final var abi = getABIFor(FUNCTION, function, redirectContract.abiResource());
        return resultWith().resultThruAbi(abi, isLiteralResult(values));
    }

    public static ContractFnResultAsserts anyResult() {
        return new ContractFnResultAsserts();
    }

    public static Object[] viaAbi(String abi, byte[] bytes) {
        com.esaulpaugh.headlong.abi.Function function = com.esaulpaugh.headlong.abi.Function.fromJson(abi);
        return function.decodeReturn(bytes).toArray();
    }

    /* Helpers to create the provider for #resultThruAbi. */
    public static Function<HapiSpec, Function<Object[], Optional<Throwable>>> isContractWith(
            ContractInfoAsserts theExpectedInfo) {
        return spec -> actualObjs -> {
            try {
                assertEquals(1, actualObjs.length, "Extra contract function return values!");
                String implicitContract = "contract" + rand.nextInt();
                ContractID contract = TxnUtils.asContractId(
                        ((Address) actualObjs[0]).toString().substring(2), spec);
                spec.registry().saveContractId(implicitContract, contract);
                HapiGetContractInfo op = getContractInfo(implicitContract).has(theExpectedInfo);
                Optional<Throwable> opError = op.execFor(spec);
                if (opError.isPresent()) {
                    throw opError.get();
                }
            } catch (Throwable t) { // NOSONAR throw from 2 lines above must be caught
                return Optional.of(t);
            }
            return Optional.empty();
        };
    }

    public static Function<HapiSpec, Function<Object[], Optional<Throwable>>> isLiteralResult(Object[] objs) {
        return ignore -> actualObjs -> matchErrors(objs, actualObjs);
    }

    public static Function<HapiSpec, Function<Object[], Optional<Throwable>>> isOneOfLiteral(Set<Object> values) {
        return ignore -> actualObjs -> {
            try {
                assertEquals(1, actualObjs.length, "Expected a single object");
                assertTrue(values.contains(actualObjs[0]), "Expected one of " + values + " but was " + actualObjs[0]);
            } catch (Exception e) {
                return Optional.of(e);
            }
            return Optional.empty();
        };
    }

    public static Function<HapiSpec, Function<Object[], Optional<Throwable>>> isEqualOrGreaterThan(
            BigInteger expected) {
        return ignore -> actualObjs -> {
            try {
                if (actualObjs[0] instanceof BigInteger actual) {
                    assertTrue(
                            actual.compareTo(expected) >= 0,
                            "Expected " + actual + " to be equal or greater than" + expected);
                    assertEquals(1, actualObjs.length, "Expected a single object");
                }
            } catch (Exception e) {
                return Optional.of(e);
            }
            return Optional.empty();
        };
    }

    public static Function<HapiSpec, Function<Object[], Optional<Throwable>>> isRandomResult(Object[] objs) {
        return ignore -> actualObjs -> validateRandomResult(objs, actualObjs);
    }

    public static Function<HapiSpec, Function<Object[], Optional<Throwable>>> isLiteralArrayResult(Object[] objs) {
        return ignore -> actualObjs -> matchErrors(objs, (Object[]) actualObjs[0]);
    }

    private static Optional<Throwable> matchErrors(Object[] expecteds, Object[] actuals) {
        try {
            for (int i = 0; i < Math.max(expecteds.length, actuals.length); i++) {
                Object expected = expecteds[i];
                Object actual = actuals[i];
                Assertions.assertNotNull(expected);
                Assertions.assertNotNull(actual);
                assertEquals(expected.getClass(), actual.getClass());
                if (expected instanceof byte[] expectedBytes) {
                    Assertions.assertArrayEquals(expectedBytes, (byte[]) actual);
                } else {
                    assertEquals(expected, actual);
                }
            }
        } catch (Exception e) {
            return Optional.of(e);
        }
        return Optional.empty();
    }

    private static Optional<Throwable> validateRandomResult(final Object[] expecteds, final Object[] actuals) {
        try {
            for (int i = 0; i < Math.max(expecteds.length, actuals.length); i++) {
                Object expected = expecteds[i];
                Object actual = actuals[i];
                Assertions.assertNotNull(expected);
                Assertions.assertNotNull(actual);
                if (expected instanceof byte[] expectedBytes) {
                    int expectedLength = expectedBytes.length;
                    assertEquals(expectedLength, ((byte[]) actual).length);
                    // reject all zero result as not random
                    Assertions.assertFalse(Arrays.equals(new byte[expectedLength], (byte[]) actual));
                } else if (expected instanceof Integer expectedInt) {
                    assertTrue(((BigInteger) actual).intValue() >= 0
                            && ((BigInteger) actual).intValue() < expectedInt.intValue());
                } else {
                    throw new Exception( // NOSONAR
                            String.format("Invalid Random result, expected %s , actual %s", expecteds[i], actuals[i]));
                }
            }
        } catch (Exception e) {
            return Optional.of(e);
        }
        return Optional.empty();
    }

    public ContractFnResultAsserts resultThruAbi(
            String abi, Function<HapiSpec, Function<Object[], Optional<Throwable>>> provider) {
        registerProvider((spec, o) -> {
            Object[] actualObjs = viaAbi(
                    abi, ((ContractFunctionResult) o).getContractCallResult().toByteArray());
            Optional<Throwable> error = provider.apply(spec).apply(actualObjs);
            if (error.isPresent()) {
                throw error.get();
            }
        });
        return this;
    }

    public ContractFnResultAsserts resultViaFunctionName(
            final String functionName,
            final String contractName,
            final Function<HapiSpec, Function<Object[], Optional<Throwable>>> provider) {
        return resultViaFunctionName(Optional.empty(), functionName, contractName, provider);
    }

    /*  Note:
     This method utilizes algorithmic extraction of a function ABI by the name of the function and the contract
     and should replace the "resultThruAbi" method, which depends on function ABI, passed as String literal.
    */
    public ContractFnResultAsserts resultViaFunctionName(
            final Optional<String> variant,
            final String functionName,
            final String contractName,
            final Function<HapiSpec, Function<Object[], Optional<Throwable>>> provider) {
        final var abi = Utils.getABIFor(variant.orElse(VARIANT_NONE), FUNCTION, functionName, contractName);
        registerProvider((spec, o) -> {
            Object[] actualObjs = viaAbi(
                    abi, ((ContractFunctionResult) o).getContractCallResult().toByteArray());
            Optional<Throwable> error = provider.apply(spec).apply(actualObjs);
            if (error.isPresent()) {
                throw error.get();
            }
        });
        return this;
    }

    public ContractFnResultAsserts contract(String contract) {
        registerIdLookupAssert(contract, r -> r.getContractID(), ContractID.class, "Bad contract!");
        return this;
    }

    public ContractFnResultAsserts hexedEvmAddress(String expected) {
        return evmAddress(ByteString.copyFrom(CommonUtils.unhex(expected)));
    }

    public ContractFnResultAsserts evmAddress(ByteString expected) {
        registerProvider((spec, o) -> {
            final var result = (ContractFunctionResult) o;
            assertTrue(result.hasEvmAddress(), "Missing EVM address, expected " + expected);
            final var actual = result.getEvmAddress().getValue();
            assertEquals(expected, actual, "Bad EVM address");
        });
        return this;
    }

    public ContractFnResultAsserts create1EvmAddress(final ByteString senderAddress, final long nonce) {
        registerProvider((spec, o) -> {
            final var result = (ContractFunctionResult) o;
            final var expectedContractAddress = org.hyperledger.besu.datatypes.Address.contractAddress(
                    org.hyperledger.besu.datatypes.Address.wrap(Bytes.wrap(senderAddress.toByteArray())), nonce);
            final var expectedAddress = ByteString.copyFrom(expectedContractAddress.toArray());
            assertTrue(result.hasEvmAddress(), "Missing EVM address, expected " + expectedAddress);
            final var actual = result.getEvmAddress().getValue();
            assertEquals(expectedAddress, actual, "Bad EVM address");
        });
        return this;
    }

    public ContractFnResultAsserts logs(ErroringAssertsProvider<List<ContractLoginfo>> provider) {
        registerProvider((spec, o) -> {
            List<ContractLoginfo> logs = ((ContractFunctionResult) o).getLogInfoList();
            ErroringAsserts<List<ContractLoginfo>> asserts = provider.assertsFor(spec);
            List<Throwable> errors = asserts.errorsIn(logs);
            AssertUtils.rethrowSummaryError(log, "Bad logs!", errors);
        });
        return this;
    }

    public ContractFnResultAsserts error(String msg) {
        registerProvider((spec, o) -> {
            ContractFunctionResult result = (ContractFunctionResult) o;
            assertEquals(
                    msg, Optional.ofNullable(result.getErrorMessage()).orElse(""), "Wrong contract function error!");
        });
        return this;
    }

    public ContractFnResultAsserts approxGasUsed(final long expected, final double allowedPercentDeviation) {
        registerProvider((spec, o) -> {
            ContractFunctionResult result = (ContractFunctionResult) o;
            final var actual = result.getGasUsed();
            final var epsilon = allowedPercentDeviation * actual / 100.0;
            assertEquals(expected, result.getGasUsed(), epsilon, "Wrong amount of gas used");
        });
        return this;
    }

    public ContractFnResultAsserts gasUsed(long gasUsed) {
        registerProvider((spec, o) -> {
            ContractFunctionResult result = (ContractFunctionResult) o;
            assertEquals(gasUsed, result.getGasUsed(), "Wrong amount of Gas was used!");
        });
        return this;
    }

    /**
     * Check that the gas used is in a given range
     * @param lowerBoundInclusive if present (not null), is the lowest acceptable value for gas used
     * @param upperBoundExclusive if present (not null), is the highest acceptable value for gas used
     */
    public ContractFnResultAsserts gasUsedIsInRange(final Long lowerBoundInclusive, final Long upperBoundExclusive) {
        registerProvider((spec, o) -> {
            ContractFunctionResult result = (ContractFunctionResult) o;
            long gasUsed = result.getGasUsed();
            if (lowerBoundInclusive != null)
                assertTrue(
                        gasUsed >= lowerBoundInclusive,
                        "gas, %d, is less than %d".formatted(gasUsed, lowerBoundInclusive));
            if (upperBoundExclusive != null)
                assertTrue(
                        gasUsed <= upperBoundExclusive,
                        "gas, %d, is more than %d".formatted(gasUsed, upperBoundExclusive));
        });
        return this;
    }

    /**
     * Adds an assertion that the gas used is the expected value, up to some small
     * variation due to differences in intrinsic gas cost for EVM payloads that
     * reference addresses that may contain different numbers of zero bytes.
     *
     * @param gasUsed the expected gas used
     * @return this
     */
    public ContractFnResultAsserts gasUsedModuloIntrinsicVariation(long gasUsed) {
        registerProvider((spec, o) -> {
            ContractFunctionResult result = (ContractFunctionResult) o;
            final var delta = Math.abs(gasUsed - result.getGasUsed());
            // Allow for up to 8 zero bytes of variation in the EVM payload; as
            // each zero/non-zero difference changes the intrinsic gas cost by
            // 8 gas units
            if (delta > 64) {
                Assertions.fail("Expected gas used to be " + gasUsed + " up to 8 differing "
                        + " zero bytes in the payload; but was " + result.getGasUsed());
            }
        });
        return this;
    }

    public ContractFnResultAsserts contractCallResult(ContractCallResult contractCallResult) {
        registerProvider((spec, o) -> {
            ContractFunctionResult result = (ContractFunctionResult) o;
            assertEquals(
                    ByteString.copyFrom(contractCallResult.getBytes().toArray()),
                    result.getContractCallResult(),
                    "Wrong contract call result!");
        });
        return this;
    }

    public ContractFnResultAsserts contractCallResultFrom(@NonNull final ContractCallResult... contractCallResults) {
        registerProvider((spec, o) -> {
            ContractFunctionResult result = (ContractFunctionResult) o;
            final var actual = result.getContractCallResult();
            var someMatch = false;
            for (final var expected : contractCallResults) {
                if (ByteString.copyFrom(expected.getBytes().toArrayUnsafe()).equals(actual)) {
                    someMatch = true;
                    break;
                }
            }
            assertTrue(someMatch, "Contract call result matched none of the expected values");
        });
        return this;
    }

    public ContractFnResultAsserts gas(long gas) {
        registerProvider((spec, o) -> {
            ContractFunctionResult result = (ContractFunctionResult) o;
            assertEquals(gas, result.getGas(), "Wrong amount of initial Gas!");
        });
        return this;
    }

    public ContractFnResultAsserts amount(long amount) {
        registerProvider((spec, o) -> {
            ContractFunctionResult result = (ContractFunctionResult) o;
            assertEquals(amount, result.getAmount(), "Wrong amount of tinybars!");
        });
        return this;
    }

    public ContractFnResultAsserts functionParameters(Bytes functionParameters) {
        registerProvider((spec, o) -> {
            ContractFunctionResult result = (ContractFunctionResult) o;
            assertEquals(
                    ByteString.copyFrom(functionParameters.toArray()),
                    result.getFunctionParameters(),
                    "Wrong function parameters!");
        });
        return this;
    }

    public ContractFnResultAsserts senderId(AccountID senderId) {
        registerProvider((spec, o) -> {
            ContractFunctionResult result = (ContractFunctionResult) o;
            assertEquals(senderId, result.getSenderId(), "Wrong senderID!");
        });
        return this;
    }

    public ContractFnResultAsserts createdContractIdsCount(int n) {
        registerProvider((spec, o) -> {
            ContractFunctionResult result = (ContractFunctionResult) o;
            assertEquals(
                    n,
                    result.getCreatedContractIDsCount(), // NOSONAR
                    "Wrong number of createdContractIds!");
        });
        return this;
    }

    public ContractFnResultAsserts contractWithNonce(final ContractID contractID, final Long expectedNonce) {
        registerProvider((spec, o) -> {
            ContractFunctionResult result = (ContractFunctionResult) o;
            Map<ContractID, Long> contractNonces = new HashMap<>();
            for (final var contractNonceItem : result.getContractNoncesList()) {
                contractNonces.put(contractNonceItem.getContractId(), contractNonceItem.getNonce());
            }
            assertEquals(expectedNonce, contractNonces.get(contractID), "Wrong nonce expectations");
        });
        return this;
    }

    public ContractFnResultAsserts signerNonce(final Long expectedNonce) {
        registerProvider((spec, o) -> {
            ContractFunctionResult result = (ContractFunctionResult) o;
            assertEquals(
                    expectedNonce.longValue(), result.getSignerNonce().getValue(), "Wrong signer nonce expectation");
        });
        return this;
    }
}
