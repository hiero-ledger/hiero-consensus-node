// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.hapi.node.base.ContractID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.tuweni.bytes.Bytes;

/**
 * Represents a system contract method: It's signature + outputs, and other information about how it is declared
 * @param function A headlong description of this method: signature + outputs, selector
 * @param systemContract Which system contract this method is part of (HAS, HSS, HTS, PRNG, EXCHANGE)
 * @param via Whether called directly or via redirect (aka proxy)
 * @param categories Set of category flags possibly empty
 * @param modifier An optional Solidity function modifier (e.g., pure or view)
 * @param variants Set of method variants (e.g., version number, or FT vs NFT), possibly empty
 * @param supportedAddresses Set of supported contract addresses, cannot be empty.  Default is ALL and is denoted by ContractID.Default.
 *                           Unlike the other fields on set like with[Contract,Via,Modifier], when setting the new value with `withSupportedAddress` method,
 *                           the existing supported addresses are replaced.
 */
public record SystemContractMethod(
        @NonNull Function function,
        @NonNull Optional<SystemContract> systemContract,
        @NonNull CallVia via,
        @NonNull EnumSet<Category> categories,
        @NonNull Optional<Modifier> modifier,
        @NonNull EnumSet<Variant> variants,
        @NonNull Set<ContractID> supportedAddresses) {

    // Denote all supported addresses by ContractID.DEFAULT
    public static final ContractID ALL_CONTRACT_ID = ContractID.DEFAULT;

    public SystemContractMethod {
        requireNonNull(function);
        requireNonNull(systemContract);
        requireNonNull(via);
        requireNonNull(categories);
        requireNonNull(modifier);
        requireNonNull(variants);
        requireNonNull(supportedAddresses);
    }

    /**
     * Factory to create a SystemContractMethod given its signature and outputs
     *
     * This SystemContractMethod is incomplete in that it doesn't have the SystemContract field,
     * which must be added later (with `withContract`)
     */
    public static SystemContractMethod declare(@NonNull final String signature, @NonNull final String outputs) {
        final var function = new com.esaulpaugh.headlong.abi.Function(signature, outputs);
        return new SystemContractMethod(
                function,
                Optional.empty(),
                CallVia.DIRECT,
                EnumSet.noneOf(Category.class),
                Optional.empty(),
                EnumSet.noneOf(Variant.class),
                Set.of(ALL_CONTRACT_ID));
    }

    /**
     * Factory to create a SystemContractMethod given its signature when it returns nothing
     *
     * This SystemContractMethod is incomplete in that it doesn't have the SystemContract field,
     * which must be added later (with `withContract`)
     */
    public static SystemContractMethod declare(@NonNull final String signature) {
        final var function = new com.esaulpaugh.headlong.abi.Function(signature);
        return new SystemContractMethod(
                function,
                Optional.empty(),
                CallVia.DIRECT,
                EnumSet.noneOf(Category.class),
                Optional.empty(),
                EnumSet.noneOf(Variant.class),
                Set.of(ALL_CONTRACT_ID));
    }

    /**
     * Verify that the SystemContractMethod was created correctly
     *
     * Specifically, check that a SystemContract was provided for it eventually
     */
    public void verifyComplete() {
        if (systemContract.isEmpty()) {
            throw new IllegalStateException("System contract %s is empty".formatted(function.getName()));
        }
    }

    /** The system contract "kind" for this SystemContractMethod */
    public enum SystemContract {
        HTS,
        HAS,
        HSS,
        PNRG,
        EXCHANGE
    }

    public interface AsSuffix {
        @NonNull
        String asSuffix();
    }

    /** Says that this SystemContractMethod is called directly or via redirect/proxy */
    public enum CallVia implements AsSuffix {
        DIRECT(""),
        PROXY("[PROXY]");

        private final String asSuffix;

        CallVia(@NonNull final String viaSuffix) {
            asSuffix = viaSuffix;
        }

        public @NonNull String asSuffix() {
            return asSuffix;
        }
    }

    /**
     * Says that this SystemContractMethod is a view function or a pure function (as in Solidity).
     *
     * If neither is specified then it is a normal (state-changing) function.
     */
    public enum Modifier implements AsSuffix {
        VIEW("VIEW"),
        PURE("PURE");

        private final String asSuffix;

        Modifier(String modifierSuffix) {
            this.asSuffix = modifierSuffix;
        }

        public @NonNull String asSuffix() {
            return asSuffix;
        }
    }

    /**
     * Categorizes the SystemContractMethod.  None, one, or more of these categories can be specified.
     *
     * Useful for grouping methods together.
     */
    public enum Category implements AsSuffix {
        ERC20("ERC20", "(can overlap with ERC721)"),
        ERC721("ERC721", "(can overlap with ERC20)"),

        SCHEDULE("SCHEDULE"),
        TOKEN_QUERY("TOKEN_QUERY", "(any token field query)"),

        ALIASES("ALIASES"),
        IS_AUTHORIZED("IS_AUTHORIZED", "(IsAuthorized, IsAuthorizedRaw)"),

        AIRDROP("AIRDROP"),
        ALLOWANCE("ALLOWANCE", "(Allowance related)"),
        APPROVAL("APPROVAL", "(Approval related)"),
        ASSOCIATION("ASSOCIATION"),
        CREATE_DELETE_TOKEN("CREATE_OR_DELETE_TOKEN", "(Create or Delete Token)"),
        FREEZE_UNFREEZE("FREEZE_UNFREEZE", "(Freeze or Unfreeze Token)"),
        KYC("KYC"),
        MINT_BURN("MINT_OR_BURN", "(Mint or Burn Token)"),
        PAUSE_UNPAUSE("PAUSE_UNPAUSE", "(Pause or Unpause Token)"),
        REJECT("REJECT_TOKEN"),
        TRANSFER("TRANSFER_TOKEN", "(any token transfer transaction"),
        UPDATE("UPDATE_TOKEN", "(any token field update)"),
        WIPE("WIPE_TOKEN");

        private final String asSuffix;
        private final String clarification;

        Category(@NonNull final String categorySuffix) {
            this.asSuffix = requireNonNull(categorySuffix);
            this.clarification = "(%s)".formatted(this.asSuffix);
        }

        Category(@NonNull final String categorySuffix, @NonNull final String clarification) {
            this.asSuffix = requireNonNull(categorySuffix);
            this.clarification = requireNonNull(clarification);
        }

        public @NonNull String asSuffix() {
            return asSuffix;
        }

        public @NonNull String clarification() {
            return clarification;
        }
    }

    /**
     * Distinguishes overloads of SystemContractMethods that have the same simple name.
     */
    public enum Variant implements AsSuffix {
        FT,
        NFT,
        V1,
        V2,
        V3,
        WITH_METADATA,
        WITH_CUSTOM_FEES;

        public @NonNull String asSuffix() {
            return name();
        }
    }

    // Fluent builders

    public @NonNull SystemContractMethod withContract(@NonNull final SystemContract systemContract) {
        return new SystemContractMethod(
                function, Optional.of(systemContract), via, categories, modifier, variants, supportedAddresses);
    }

    public @NonNull SystemContractMethod withVia(@NonNull final CallVia via) {
        return new SystemContractMethod(
                function, systemContract, via, categories, modifier, variants, supportedAddresses);
    }

    public @NonNull SystemContractMethod withCategories(@NonNull final Category... categories) {
        final var c = EnumSet.copyOf(this.categories);
        c.addAll(Arrays.asList(categories));
        return new SystemContractMethod(function, systemContract, via, c, modifier, variants, supportedAddresses);
    }

    public @NonNull SystemContractMethod withCategory(@NonNull final Category category) {
        final var c = EnumSet.copyOf(categories);
        c.add(category);
        return new SystemContractMethod(function, systemContract, via, c, modifier, variants, supportedAddresses);
    }

    public @NonNull SystemContractMethod withModifier(@NonNull final Modifier modifier) {
        return new SystemContractMethod(
                function, systemContract, via, categories, Optional.of(modifier), variants, supportedAddresses);
    }

    public @NonNull SystemContractMethod withVariants(@NonNull final Variant... variants) {
        final var v = EnumSet.copyOf(this.variants);
        v.addAll(Arrays.asList(variants));
        return new SystemContractMethod(function, systemContract, via, categories, modifier, v, supportedAddresses);
    }

    public @NonNull SystemContractMethod withVariant(@NonNull final Variant variant) {
        final var v = EnumSet.copyOf(variants);
        v.add(variant);
        return new SystemContractMethod(function, systemContract, via, categories, modifier, v, supportedAddresses);
    }

    public @NonNull SystemContractMethod withSupportedAddresses(@NonNull final ContractID... supportedAddresses) {
        // Unlike the other with methods, this one replaces the set of supported addresses as the default of ALL should
        // be overridden
        if (supportedAddresses.length == 0) {
            return new SystemContractMethod(
                    function, systemContract, via, categories, modifier, variants, Set.of(ALL_CONTRACT_ID));
        }
        final var sa = new java.util.HashSet<ContractID>();
        sa.addAll(Arrays.asList(supportedAddresses));
        return new SystemContractMethod(function, systemContract, via, categories, modifier, variants, sa);
    }

    public @NonNull SystemContractMethod withSupportedAddress(@NonNull final ContractID supportedAddress) {
        return withSupportedAddresses(supportedAddress);
    }

    // Forwarding to com.esaulpaugh.headlong.abi.Function

    public Tuple decodeCall(byte[] call) {
        return function.decodeCall(call);
    }

    public ByteBuffer encodeCall(Tuple tuple) {
        return function.encodeCall(tuple);
    }

    public ByteBuffer encodeCallWithArgs(Object... args) {
        return function.encodeCallWithArgs(args);
    }

    public <T extends Tuple> TupleType<T> getOutputs() {
        return function.getOutputs();
    }

    public @NonNull String signature() {
        return function.getCanonicalSignature();
    }

    public @NonNull String signatureWithReturn() {
        return signature() + ':' + function.getOutputs();
    }

    public @NonNull byte[] selector() {
        return function.selector();
    }

    public long selectorLong() {
        return Bytes.wrap(function.selector()).toLong(ByteOrder.BIG_ENDIAN);
    }

    public @NonNull String selectorHex() {
        return function.selectorHex();
    }

    public boolean hasVariant(@NonNull final Variant variant) {
        return variants.contains(variant);
    }

    public boolean hasCategory(@NonNull final Category category) {
        return categories.contains(category);
    }

    public boolean hasCategory(@NonNull final Set<Category> categories) {
        var intersection = this.categories.clone();
        intersection.retainAll(categories);
        return !intersection.isEmpty();
    }

    public boolean hasSupportedAddress(@NonNull final ContractID supportedAddress) {
        return supportedAddresses.contains(ContractID.DEFAULT) || supportedAddresses.contains(supportedAddress);
    }

    /**
     * Return the method's name (simple, undecorated)
     * @return the method's name
     */
    public @NonNull String methodName() {
        return function.getName();
    }

    /**
     * Return the method's name with variant decorations
     *
     * The variant decorations (e.g., `_V1` and `_V2`) distinguish overloads of the method
     * @return the method names with the variants as suffix
     */
    public @NonNull String variatedMethodName() {
        return methodName() + variantsSuffix();
    }

    public @NonNull String fullyDecoratedMethodName() {
        var name = qualifiedMethodName();
        name += modifiersSuffix();
        name += categoriesSuffix();
        return name;
    }

    /**
     * "Qualified" method name has the contract name in front of it, and the variants appended to it.
     */
    public @NonNull String qualifiedMethodName() {
        final var systemContractName = systemContract.map(Enum::name).orElse("???");
        final var methodName =
                switch (via) {
                    case DIRECT -> systemContractName + "." + variatedMethodName();
                    case PROXY -> systemContractName + "(PROXY)." + variatedMethodName();
                };
        return methodName;
    }

    public @NonNull String variantsSuffix() {
        if (variants.isEmpty()) return "";
        return variants.stream().map(Variant::asSuffix).sorted().collect(Collectors.joining("_", "_", ""));
    }

    public @NonNull String categoriesSuffix() {
        if (categories.isEmpty()) return "";
        return categories.stream().map(Category::asSuffix).sorted().collect(Collectors.joining(",", "_[", "]"));
    }

    public @NonNull String modifiersSuffix() {
        if (modifier.isEmpty()) return "";
        return "_[" + modifier.get().asSuffix() + "]";
    }
}
