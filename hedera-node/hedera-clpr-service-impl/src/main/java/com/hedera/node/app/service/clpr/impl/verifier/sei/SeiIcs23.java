// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.sei;

import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.WIRE_LENGTH_DELIMITED;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.WIRE_VARINT;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.concat;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.varint;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * ICS-23 existence-proof verification for the two proof layers a Sei {@code abci_query}
 * with {@code prove=true} returns:
 * <ol>
 *   <li>{@code ics23:iavl} ({@link #IAVL_SPEC}) — proves a key/value (e.g. an EVM storage
 *       slot under sei-chain's {@code 0x03 || address || slot} key) up to the {@code evm}
 *       module store root;</li>
 *   <li>{@code ics23:simple} ({@link #TENDERMINT_SPEC}) — proves the store-name/store-root
 *       pair up to the chain's {@code app_hash}.</li>
 * </ol>
 *
 * <p>Membership proofs are used for normal storage slots; non-membership proofs are accepted
 * only by callers that explicitly request them. Hand-rolled for the same reason the QBFT
 * verifier hand-rolls its MPT verification: the logic is small, consensus-critical, and better
 * kept explicit than imported.
 */
public final class SeiIcs23 {

    // ics23 HashOp / LengthOp enum values used by the IAVL and Tendermint specs
    private static final int HASH_NO_HASH = 0;
    private static final int HASH_SHA256 = 1;
    private static final int LENGTH_VAR_PROTO = 1;

    /** ics23 spec for IAVL trees (Cosmos module stores). */
    public static final Spec IAVL_SPEC =
            new Spec(new LeafSpec(HASH_SHA256, HASH_NO_HASH, HASH_SHA256, LENGTH_VAR_PROTO, new byte[] {0}), 4, 12, 33);

    /** ics23 spec for the Tendermint simple Merkle tree (the multistore commitment). */
    public static final Spec TENDERMINT_SPEC =
            new Spec(new LeafSpec(HASH_SHA256, HASH_NO_HASH, HASH_SHA256, LENGTH_VAR_PROTO, new byte[] {0}), 1, 1, 32);

    private SeiIcs23() {}

    /**
     * The shape an {@code ics23.LeafOp} must take under a spec: how key and value are
     * pre-hashed and length-prefixed before hashing with the (spec-mandated) prefix.
     */
    public record LeafSpec(
            int hashOp,
            int prehashKey,
            int prehashValue,
            int lengthOp,
            @NonNull byte[] prefix) {}

    /**
     * An ics23 proof spec restricted to the binary trees Sei uses (child order [0,1]).
     *
     * @param leaf required leaf-op shape
     * @param minPrefixLength minimum inner-op prefix length
     * @param maxPrefixLength maximum inner-op prefix length for a left child
     * @param childSize size of an encoded child hash within an inner op
     */
    public record Spec(@NonNull LeafSpec leaf, int minPrefixLength, int maxPrefixLength, int childSize) {}

    /** A parsed {@code ics23.ExistenceProof}. */
    public record ExistenceProof(
            @NonNull byte[] key,
            @NonNull byte[] value,
            @NonNull LeafOp leaf,
            @NonNull List<InnerOp> path) {}

    /** A parsed {@code ics23.NonExistenceProof}. */
    public record NonExistenceProof(
            @NonNull byte[] key,
            @Nullable ExistenceProof left,
            @Nullable ExistenceProof right) {}

    /** A parsed {@code ics23.CommitmentProof}. Exactly one field is non-null. */
    public record CommitmentProof(
            @Nullable ExistenceProof existence, @Nullable NonExistenceProof nonExistence) {
        public CommitmentProof {
            if ((existence == null) == (nonExistence == null)) {
                throw ProofException.sei("CommitmentProof must contain exactly one proof variant");
            }
        }
    }

    /** A parsed {@code ics23.LeafOp}. */
    public record LeafOp(
            int hashOp,
            int prehashKey,
            int prehashValue,
            int lengthOp,
            @NonNull byte[] prefix) {}

    /** A parsed {@code ics23.InnerOp}. */
    public record InnerOp(
            int hashOp, @NonNull byte[] prefix, @NonNull byte[] suffix) {}

    /**
     * Parses an {@code ics23.CommitmentProof}, accepting only the {@code exist} variant.
     *
     * @throws ProofException if the bytes are not a well-formed existence proof
     */
    @NonNull
    public static ExistenceProof parseCommitmentProof(@NonNull final byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        final var reader = new SeiProto.Reader(bytes);
        byte[] exist = null;
        while (reader.hasMore()) {
            final int tag = reader.readTag();
            if (tag != ((1 << 3) | WIRE_LENGTH_DELIMITED)) {
                throw ProofException.sei(
                        "only existence proofs are supported (unexpected CommitmentProof tag " + tag + ")");
            }
            if (exist != null) {
                throw ProofException.sei("CommitmentProof has multiple exist fields");
            }
            exist = reader.readBytes();
        }
        if (exist == null) {
            throw ProofException.sei("CommitmentProof has no existence proof");
        }
        return parseExistenceProof(exist);
    }

    /**
     * Parses an {@code ics23.CommitmentProof}, accepting either the {@code exist} or
     * {@code nonexist} variant.
     *
     * @throws ProofException if the bytes are not a well-formed supported proof
     */
    @NonNull
    public static CommitmentProof parseAnyCommitmentProof(@NonNull final byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        final var reader = new SeiProto.Reader(bytes);
        byte[] exist = null;
        byte[] nonexist = null;
        while (reader.hasMore()) {
            final int tag = reader.readTag();
            if (tag == ((1 << 3) | WIRE_LENGTH_DELIMITED)) {
                if (exist != null || nonexist != null) {
                    throw ProofException.sei("CommitmentProof has multiple proof variants");
                }
                exist = reader.readBytes();
            } else if (tag == ((2 << 3) | WIRE_LENGTH_DELIMITED)) {
                if (exist != null || nonexist != null) {
                    throw ProofException.sei("CommitmentProof has multiple proof variants");
                }
                nonexist = reader.readBytes();
            } else {
                throw ProofException.sei(
                        "only existence or non-existence proofs are supported (unexpected CommitmentProof tag " + tag
                                + ")");
            }
        }
        if (exist == null && nonexist == null) {
            throw ProofException.sei("CommitmentProof has no supported proof");
        }
        return exist != null
                ? new CommitmentProof(parseExistenceProof(exist), null)
                : new CommitmentProof(null, parseNonExistenceProof(nonexist));
    }

    /**
     * Verifies that {@code key -> value} is a member of the tree with the given root,
     * per the given spec.
     *
     * @throws ProofException if the proof does not bind exactly this key/value to this root
     */
    public static void verifyMembership(
            @NonNull final ExistenceProof proof,
            @NonNull final Spec spec,
            @NonNull final byte[] root,
            @NonNull final byte[] key,
            @NonNull final byte[] value) {
        if (!Arrays.equals(proof.key(), key)) {
            throw ProofException.sei("proof is for a different key");
        }
        if (!Arrays.equals(proof.value(), value)) {
            throw ProofException.sei("proof is for a different value");
        }
        if (!Arrays.equals(existenceRoot(proof, spec), root)) {
            throw ProofException.sei("proof root does not match expected root");
        }
    }

    /**
     * Verifies that {@code key} is absent from the tree with the given root, per the given spec.
     *
     * @throws ProofException if the proof does not bind this absence to the root
     */
    public static void verifyNonMembership(
            @NonNull final NonExistenceProof proof,
            @NonNull final Spec spec,
            @NonNull final byte[] root,
            @NonNull final byte[] key) {
        if (!Arrays.equals(proof.key(), key)) {
            throw ProofException.sei("non-existence proof is for a different key");
        }

        final ExistenceProof left = proof.left();
        final ExistenceProof right = proof.right();
        if (left == null && right == null) {
            throw ProofException.sei("non-existence proof has neither left nor right neighbor");
        }

        if (left != null) {
            verifyMembership(left, spec, root, left.key(), left.value());
            if (compareLexicographic(key, left.key()) <= 0) {
                throw ProofException.sei("non-existence key is not right of left neighbor");
            }
        }
        if (right != null) {
            verifyMembership(right, spec, root, right.key(), right.value());
            if (compareLexicographic(key, right.key()) >= 0) {
                throw ProofException.sei("non-existence key is not left of right neighbor");
            }
        }

        if (left == null) {
            if (!isLeftMostPath(right.path(), spec)) {
                throw ProofException.sei("right neighbor is not left-most");
            }
        } else if (right == null) {
            if (!isRightMostPath(left.path(), spec)) {
                throw ProofException.sei("left neighbor is not right-most");
            }
        } else if (!isLeftNeighbor(left.path(), right.path(), spec)) {
            throw ProofException.sei("non-existence neighbors are not adjacent");
        }
    }

    /**
     * Computes the root committed to by an existence proof, after checking every op
     * against the spec (so a leaf op cannot be smuggled in as an inner op or vice versa).
     */
    @NonNull
    public static byte[] existenceRoot(@NonNull final ExistenceProof proof, @NonNull final Spec spec) {
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(spec, "spec");
        if (proof.key().length == 0) {
            throw ProofException.sei("existence proof has empty key");
        }
        if (proof.value().length == 0) {
            throw ProofException.sei("existence proof has empty value");
        }
        ensureLeafMatchesSpec(proof.leaf(), spec);
        byte[] node = applyLeaf(proof.leaf(), proof.key(), proof.value());
        for (final InnerOp op : proof.path()) {
            ensureInnerMatchesSpec(op, spec);
            node = SeiMerkle.sha256(concat(op.prefix(), node, op.suffix()));
        }
        return node;
    }

    private static void ensureLeafMatchesSpec(final LeafOp leaf, final Spec spec) {
        final LeafSpec expected = spec.leaf();
        if (leaf.hashOp() != expected.hashOp()
                || leaf.prehashKey() != expected.prehashKey()
                || leaf.prehashValue() != expected.prehashValue()
                || leaf.lengthOp() != expected.lengthOp()) {
            throw ProofException.sei("leaf op does not match spec");
        }
        if (!startsWith(leaf.prefix(), expected.prefix())) {
            throw ProofException.sei("leaf prefix does not start with spec prefix");
        }
    }

    private static void ensureInnerMatchesSpec(final InnerOp op, final Spec spec) {
        if (op.hashOp() != HASH_SHA256) {
            throw ProofException.sei("inner op hash must be SHA256");
        }
        if (startsWith(op.prefix(), spec.leaf().prefix())) {
            throw ProofException.sei("inner op prefix collides with leaf prefix");
        }
        // For binary trees the child may sit left or right of the prefix, so a right-child
        // prefix may carry one extra encoded child before it.
        final int maxPrefix = spec.maxPrefixLength() + spec.childSize();
        if (op.prefix().length < spec.minPrefixLength() || op.prefix().length > maxPrefix) {
            throw ProofException.sei("inner op prefix length " + op.prefix().length + " outside ["
                    + spec.minPrefixLength() + ", " + maxPrefix + "]");
        }
        if (op.suffix().length % spec.childSize() != 0) {
            throw ProofException.sei(
                    "inner op suffix length " + op.suffix().length + " not a multiple of " + spec.childSize());
        }
    }

    private static byte[] applyLeaf(final LeafOp leaf, final byte[] key, final byte[] value) {
        // prehashKey is NO_HASH and lengthOp is VAR_PROTO in both supported specs
        final byte[] encodedKey = concat(varint(key.length), key);
        final byte[] hashedValue = SeiMerkle.sha256(value);
        final byte[] encodedValue = concat(varint(hashedValue.length), hashedValue);
        return SeiMerkle.sha256(concat(leaf.prefix(), encodedKey, encodedValue));
    }

    @NonNull
    private static NonExistenceProof parseNonExistenceProof(final byte[] bytes) {
        final var reader = new SeiProto.Reader(bytes);
        byte[] key = null;
        ExistenceProof left = null;
        ExistenceProof right = null;
        while (reader.hasMore()) {
            final int tag = reader.readTag();
            switch (tag) {
                case (1 << 3) | WIRE_LENGTH_DELIMITED -> key = reader.readBytes();
                case (2 << 3) | WIRE_LENGTH_DELIMITED -> left = parseExistenceProof(reader.readBytes());
                case (3 << 3) | WIRE_LENGTH_DELIMITED -> right = parseExistenceProof(reader.readBytes());
                default -> throw ProofException.sei("unexpected NonExistenceProof tag " + tag);
            }
        }
        if (key == null) {
            throw ProofException.sei("NonExistenceProof missing key");
        }
        return new NonExistenceProof(key, left, right);
    }

    @NonNull
    private static ExistenceProof parseExistenceProof(final byte[] bytes) {
        final var reader = new SeiProto.Reader(bytes);
        byte[] key = null;
        byte[] value = null;
        LeafOp leaf = null;
        final List<InnerOp> path = new ArrayList<>();
        while (reader.hasMore()) {
            final int tag = reader.readTag();
            switch (tag) {
                case (1 << 3) | WIRE_LENGTH_DELIMITED -> key = reader.readBytes();
                case (2 << 3) | WIRE_LENGTH_DELIMITED -> value = reader.readBytes();
                case (3 << 3) | WIRE_LENGTH_DELIMITED -> leaf = parseLeafOp(reader.readBytes());
                case (4 << 3) | WIRE_LENGTH_DELIMITED -> path.add(parseInnerOp(reader.readBytes()));
                default -> throw ProofException.sei("unexpected ExistenceProof tag " + tag);
            }
        }
        if (key == null || value == null || leaf == null) {
            throw ProofException.sei("ExistenceProof missing key, value, or leaf op");
        }
        return new ExistenceProof(key, value, leaf, List.copyOf(path));
    }

    @NonNull
    private static LeafOp parseLeafOp(final byte[] bytes) {
        final var reader = new SeiProto.Reader(bytes);
        int hashOp = 0;
        int prehashKey = 0;
        int prehashValue = 0;
        int lengthOp = 0;
        byte[] prefix = new byte[0];
        while (reader.hasMore()) {
            final int tag = reader.readTag();
            switch (tag) {
                case (1 << 3) | WIRE_VARINT -> hashOp = (int) reader.readVarint();
                case (2 << 3) | WIRE_VARINT -> prehashKey = (int) reader.readVarint();
                case (3 << 3) | WIRE_VARINT -> prehashValue = (int) reader.readVarint();
                case (4 << 3) | WIRE_VARINT -> lengthOp = (int) reader.readVarint();
                case (5 << 3) | WIRE_LENGTH_DELIMITED -> prefix = reader.readBytes();
                default -> throw ProofException.sei("unexpected LeafOp tag " + tag);
            }
        }
        return new LeafOp(hashOp, prehashKey, prehashValue, lengthOp, prefix);
    }

    @NonNull
    private static InnerOp parseInnerOp(final byte[] bytes) {
        final var reader = new SeiProto.Reader(bytes);
        int hashOp = 0;
        byte[] prefix = new byte[0];
        byte[] suffix = new byte[0];
        while (reader.hasMore()) {
            final int tag = reader.readTag();
            switch (tag) {
                case (1 << 3) | WIRE_VARINT -> hashOp = (int) reader.readVarint();
                case (2 << 3) | WIRE_LENGTH_DELIMITED -> prefix = reader.readBytes();
                case (3 << 3) | WIRE_LENGTH_DELIMITED -> suffix = reader.readBytes();
                default -> throw ProofException.sei("unexpected InnerOp tag " + tag);
            }
        }
        return new InnerOp(hashOp, prefix, suffix);
    }

    private static boolean startsWith(final byte[] bytes, final byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        return Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static boolean isLeftMostPath(@NonNull final List<InnerOp> path, @NonNull final Spec spec) {
        for (final InnerOp op : path) {
            if (branchIndex(op, spec) != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isRightMostPath(@NonNull final List<InnerOp> path, @NonNull final Spec spec) {
        for (final InnerOp op : path) {
            if (branchIndex(op, spec) != 1) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLeftNeighbor(
            @NonNull final List<InnerOp> leftPath, @NonNull final List<InnerOp> rightPath, @NonNull final Spec spec) {
        if (leftPath.isEmpty() || rightPath.isEmpty()) {
            return false;
        }
        int leftIndex = leftPath.size() - 1;
        int rightIndex = rightPath.size() - 1;
        while (leftIndex >= 0 && rightIndex >= 0 && sameInnerOp(leftPath.get(leftIndex), rightPath.get(rightIndex))) {
            leftIndex--;
            rightIndex--;
        }
        if (leftIndex < 0 || rightIndex < 0) {
            return false;
        }
        if (branchIndex(leftPath.get(leftIndex), spec) != 0 || branchIndex(rightPath.get(rightIndex), spec) != 1) {
            return false;
        }
        return isRightMostPath(leftPath.subList(0, leftIndex), spec)
                && isLeftMostPath(rightPath.subList(0, rightIndex), spec);
    }

    private static int branchIndex(@NonNull final InnerOp op, @NonNull final Spec spec) {
        if (op.suffix().length == spec.childSize()
                && op.prefix().length >= spec.minPrefixLength()
                && op.prefix().length <= spec.maxPrefixLength()) {
            return 0;
        }
        if (op.suffix().length == 0
                && op.prefix().length >= spec.minPrefixLength() + spec.childSize()
                && op.prefix().length <= spec.maxPrefixLength() + spec.childSize()) {
            return 1;
        }
        throw ProofException.sei("inner op does not describe a binary branch");
    }

    private static boolean sameInnerOp(@NonNull final InnerOp left, @NonNull final InnerOp right) {
        return left.hashOp() == right.hashOp()
                && Arrays.equals(left.prefix(), right.prefix())
                && Arrays.equals(left.suffix(), right.suffix());
    }

    private static int compareLexicographic(final byte[] left, final byte[] right) {
        final int len = Math.min(left.length, right.length);
        for (int i = 0; i < len; i++) {
            final int a = left[i] & 0xFF;
            final int b = right[i] & 0xFF;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return Integer.compare(left.length, right.length);
    }
}
