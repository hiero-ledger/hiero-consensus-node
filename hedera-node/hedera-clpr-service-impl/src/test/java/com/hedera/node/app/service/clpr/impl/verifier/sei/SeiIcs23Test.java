// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.sei;

import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.bytesField;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.concat;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.messageField;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.varintField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeiIcs23Test {
    private static final byte[] KEY = {1, 2, 3};
    private static final byte[] VALUE = {4, 5, 6};

    @Test
    void parsesAndVerifiesLeafOnlyMembership() {
        final var proof =
                SeiIcs23.parseCommitmentProof(commitmentProof(KEY, VALUE, leafOp(1, 0, 1, 1, new byte[] {0})));
        final byte[] root = SeiIcs23.existenceRoot(proof, SeiIcs23.TENDERMINT_SPEC);

        SeiIcs23.verifyMembership(proof, SeiIcs23.TENDERMINT_SPEC, root, KEY, VALUE);
        assertThat(root).hasSize(32);
    }

    @Test
    void rejectsNullInputs() {
        final var proof =
                new SeiIcs23.ExistenceProof(KEY, VALUE, new SeiIcs23.LeafOp(1, 0, 1, 1, new byte[] {0}), List.of());

        assertThatNullPointerException().isThrownBy(() -> SeiIcs23.parseCommitmentProof(null));
        assertThatNullPointerException().isThrownBy(() -> SeiIcs23.existenceRoot(null, SeiIcs23.IAVL_SPEC));
        assertThatNullPointerException().isThrownBy(() -> SeiIcs23.existenceRoot(proof, null));
    }

    @Test
    void rejectsUnsupportedCommitmentProofShapes() {
        final byte[] exist = existenceProof(KEY, VALUE, leafOp(1, 0, 1, 1, new byte[] {0}));

        assertProofRejected(varintField(2, 1), "only existence proofs");
        assertProofRejected(concat(messageField(1, exist), messageField(1, exist)), "multiple exist");
        assertProofRejected(new byte[0], "no existence proof");
    }

    @Test
    void parsesAndVerifiesRightEdgeNonMembership() {
        final byte[] leaf = leafOp(1, 0, 1, 1, new byte[] {0});
        final byte[] leftExistence = existenceProof(KEY, VALUE, leaf);
        final var leftProof = SeiIcs23.parseCommitmentProof(messageField(1, leftExistence));
        final byte[] root = SeiIcs23.existenceRoot(leftProof, SeiIcs23.TENDERMINT_SPEC);
        final byte[] missingKey = {1, 2, 4};
        final byte[] nonExistence = concat(bytesField(1, missingKey), messageField(2, leftExistence));

        final var proof = SeiIcs23.parseAnyCommitmentProof(messageField(2, nonExistence));

        SeiIcs23.verifyNonMembership(proof.nonExistence(), SeiIcs23.TENDERMINT_SPEC, root, missingKey);
        assertThatThrownBy(() -> SeiIcs23.parseCommitmentProof(messageField(2, nonExistence)))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("only existence proofs");
        assertThatThrownBy(
                        () -> SeiIcs23.verifyNonMembership(proof.nonExistence(), SeiIcs23.TENDERMINT_SPEC, root, KEY))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("different key");
    }

    @Test
    void rejectsWrongMembershipInputs() {
        final var proof =
                SeiIcs23.parseCommitmentProof(commitmentProof(KEY, VALUE, leafOp(1, 0, 1, 1, new byte[] {0})));
        final byte[] root = SeiIcs23.existenceRoot(proof, SeiIcs23.TENDERMINT_SPEC);

        assertThatThrownBy(
                        () -> SeiIcs23.verifyMembership(proof, SeiIcs23.TENDERMINT_SPEC, root, new byte[] {9}, VALUE))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("different key");
        assertThatThrownBy(() -> SeiIcs23.verifyMembership(proof, SeiIcs23.TENDERMINT_SPEC, root, KEY, new byte[] {9}))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("different value");
        final byte[] badRoot = root.clone();
        badRoot[0] ^= 1;
        assertThatThrownBy(() -> SeiIcs23.verifyMembership(proof, SeiIcs23.TENDERMINT_SPEC, badRoot, KEY, VALUE))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("root does not match");
    }

    @Test
    void rejectsEmptyKeyOrValue() {
        final var leaf = new SeiIcs23.LeafOp(1, 0, 1, 1, new byte[] {0});

        assertThatThrownBy(() -> SeiIcs23.existenceRoot(
                        new SeiIcs23.ExistenceProof(new byte[0], VALUE, leaf, List.of()), SeiIcs23.TENDERMINT_SPEC))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("empty key");
        assertThatThrownBy(() -> SeiIcs23.existenceRoot(
                        new SeiIcs23.ExistenceProof(KEY, new byte[0], leaf, List.of()), SeiIcs23.TENDERMINT_SPEC))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("empty value");
    }

    @Test
    void rejectsLeafOpsOutsideSpec() {
        assertExistenceRootRejected(
                new SeiIcs23.LeafOp(0, 0, 1, 1, new byte[] {0}), List.of(), "leaf op does not match spec");
        assertExistenceRootRejected(
                new SeiIcs23.LeafOp(1, 0, 1, 1, new byte[] {1}), List.of(), "leaf prefix does not start");
        assertExistenceRootRejected(
                new SeiIcs23.LeafOp(1, 0, 1, 1, new byte[0]), List.of(), "leaf prefix does not start");
    }

    @Test
    void rejectsInnerOpsOutsideSpec() {
        final var leaf = new SeiIcs23.LeafOp(1, 0, 1, 1, new byte[] {0});

        assertExistenceRootRejected(leaf, List.of(new SeiIcs23.InnerOp(0, new byte[] {1}, new byte[0])), "SHA256");
        assertExistenceRootRejected(leaf, List.of(new SeiIcs23.InnerOp(1, new byte[] {0}, new byte[0])), "collides");
        assertExistenceRootRejected(leaf, List.of(new SeiIcs23.InnerOp(1, new byte[0], new byte[0])), "outside");
        assertExistenceRootRejected(
                leaf, List.of(new SeiIcs23.InnerOp(1, new byte[] {1}, new byte[] {1})), "not a multiple");
    }

    @Test
    void rejectsMalformedExistenceProofWireFields() {
        assertProofRejected(messageField(1, varintField(9, 1)), "unexpected ExistenceProof tag");
        assertProofRejected(messageField(1, concat(bytesField(1, KEY), bytesField(2, VALUE))), "missing key");
    }

    @Test
    void parsesLeafPrehashKeyFieldAndRejectsUnexpectedLeafTag() {
        final byte[] leafWithPrehashKey = leafOp(1, 7, 1, 1, new byte[] {0});
        final var parsed = SeiIcs23.parseCommitmentProof(commitmentProof(KEY, VALUE, leafWithPrehashKey));
        assertThatThrownBy(() -> SeiIcs23.existenceRoot(parsed, SeiIcs23.TENDERMINT_SPEC))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("leaf op does not match spec");

        assertProofRejected(commitmentProof(KEY, VALUE, varintField(9, 1)), "unexpected LeafOp tag");
    }

    @Test
    void rejectsUnexpectedInnerTag() {
        final byte[] innerWithUnknownTag = varintField(9, 1);
        assertProofRejected(
                commitmentProof(KEY, VALUE, leafOp(1, 0, 1, 1, new byte[] {0}), messageField(4, innerWithUnknownTag)),
                "unexpected InnerOp tag");
    }

    private static void assertExistenceRootRejected(
            final SeiIcs23.LeafOp leaf, final List<SeiIcs23.InnerOp> path, final String message) {
        assertThatThrownBy(() -> SeiIcs23.existenceRoot(
                        new SeiIcs23.ExistenceProof(KEY, VALUE, leaf, path), SeiIcs23.TENDERMINT_SPEC))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining(message);
    }

    private static void assertProofRejected(final byte[] proofBytes, final String message) {
        assertThatThrownBy(() -> SeiIcs23.parseCommitmentProof(proofBytes))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining(message);
    }

    private static byte[] commitmentProof(
            final byte[] key, final byte[] value, final byte[] leafOp, final byte[]... pathFields) {
        return messageField(1, existenceProof(key, value, leafOp, pathFields));
    }

    private static byte[] existenceProof(
            final byte[] key, final byte[] value, final byte[] leafOp, final byte[]... pathFields) {
        return concat(bytesField(1, key), bytesField(2, value), messageField(3, leafOp), concat(pathFields));
    }

    private static byte[] leafOp(
            final int hashOp, final int prehashKey, final int prehashValue, final int lengthOp, final byte[] prefix) {
        return concat(
                varintField(1, hashOp),
                varintField(2, prehashKey),
                varintField(3, prehashValue),
                varintField(4, lengthOp),
                bytesField(5, prefix));
    }
}
