// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.utility;

import static com.swirlds.common.formatting.TextEffect.BRIGHT_CYAN;
import static com.swirlds.common.formatting.TextEffect.BRIGHT_RED;
import static com.swirlds.common.formatting.TextEffect.BRIGHT_YELLOW;
import static com.swirlds.common.formatting.TextEffect.GRAY;
import static com.swirlds.common.formatting.TextEffect.WHITE;

import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.formatting.TextTable;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.iterators.MerkleIterationOrder;
import com.swirlds.common.merkle.iterators.MerkleIterator;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.merkle.route.MerkleRouteUtils;
import com.swirlds.common.utility.Labeled;
import com.swirlds.common.utility.Mnemonics;
import java.util.function.Predicate;
import org.hiero.consensus.model.crypto.Hash;

/**
 * A utility for drawing merkle trees in a human viewable format.
 */
public class MerkleTreeVisualizer {
    private static final String INDENT = "   ";

    private final MerkleNode root;
    private boolean useColors = false;
    private boolean useMnemonics = true;
    private boolean useHashes = false;
    private boolean useLabels = true;
    private boolean useFullRoute = true;
    private int hashLength = -1;
    private int depth = 10;
    private boolean ignoreDepthAnnotations = false;

    /**
     * Create a new merkle tree visualizer.
     *
     * @param root the root of the tree (or subtree)
     */
    public MerkleTreeVisualizer(final MerkleNode root) {
        this.root = root;
    }

    /**
     * Set whether to use colors in the output.
     *
     * @param useColors whether to use colors
     * @return this object
     */
    public MerkleTreeVisualizer setUseColors(final boolean useColors) {
        this.useColors = useColors;
        return this;
    }

    /**
     * Set whether to use mnemonics in the output.
     *
     * @param useMnemonics whether to use mnemonics
     * @return this object
     */
    public MerkleTreeVisualizer setUseMnemonics(final boolean useMnemonics) {
        this.useMnemonics = useMnemonics;
        return this;
    }

    /**
     * Set whether to use hashes in the output.
     *
     * @param useHashes whether to use hashes
     * @return this object
     */
    public MerkleTreeVisualizer setUseHashes(final boolean useHashes) {
        this.useHashes = useHashes;
        return this;
    }

    /**
     * Set whether to use full route in the output.
     *
     * @param useFullRoute whether to use full route
     * @return this object
     */
    public MerkleTreeVisualizer setUseFullRoute(final boolean useFullRoute) {
        this.useFullRoute = useFullRoute;
        return this;
    }

    /**
     * Set whether to use labels in the output.
     *
     * @param useLabels whether to use labels
     * @return this object
     */
    public MerkleTreeVisualizer setUseLabels(final boolean useLabels) {
        this.useLabels = useLabels;
        return this;
    }

    /**
     * Set the length of the hash to display.
     *
     * @param hashLength the length of the hash to display, or -1 if the full hash should be displayed
     * @return this object
     */
    public MerkleTreeVisualizer setHashLength(final int hashLength) {
        this.hashLength = hashLength;
        return this;
    }

    /**
     * Set the maximum depth to print.
     *
     * @param depth the maximum depth to print
     * @return this object
     */
    public MerkleTreeVisualizer setDepth(final int depth) {
        this.depth = depth;
        return this;
    }

    /**
     * Set whether to ignore the depth annotations on maps.
     *
     * @param ignoreDepthAnnotations whether to ignore the depth annotations on maps
     * @return this object
     */
    public MerkleTreeVisualizer setIgnoreDepthAnnotations(final boolean ignoreDepthAnnotations) {
        this.ignoreDepthAnnotations = ignoreDepthAnnotations;
        return this;
    }

    /**
     * Render the tree in a human-readable format.
     *
     * @return a string representation of the tree
     */
    public String render() {
        final StringBuilder sb = new StringBuilder();
        render(sb);
        return sb.toString();
    }

    /**
     * Render the tree in a human-readable format.
     *
     * @param sb the string builder to append to
     */
    public void render(final StringBuilder sb) {

        final int rootDepth = root == null ? 0 : root.getDepth();
        final int maxDepth = rootDepth + depth;

        final Predicate<MerkleInternal> filter = (final MerkleInternal parent) -> {
            final boolean ignore =
                    !ignoreDepthAnnotations && parent.getClass().isAnnotationPresent(DebugIterationEndpoint.class);
            final boolean tooDeep = parent.getRoute().size() >= maxDepth;
            return !ignore && !tooDeep;
        };

        final MerkleIterator<MerkleNode> iterator = new MerkleIterator<>(root)
                .ignoreNull(false)
                .setOrder(MerkleIterationOrder.PRE_ORDERED_DEPTH_FIRST)
                .setDescendantFilter(filter);

        final TextTable table = new TextTable().setExtraPadding(3).setBordersEnabled(false);

        iterator.forEachRemaining((final MerkleNode node) -> {
            final MerkleRoute route = iterator.getRoute();

            final String indentation = INDENT.repeat(route.size() - rootDepth);

            // Get a string representing the child's index in its parent
            final String indexString;
            if (route.isEmpty()) {
                indexString = "(root)";
            } else {
                indexString = Integer.toString(route.getStep(-1));
            }

            final String className = node == null ? "null" : node.getClass().getSimpleName();

            final String formattedIndexString = useColors ? BRIGHT_CYAN.apply(indexString) : indexString;
            final String formattedClassName = useColors ? BRIGHT_YELLOW.apply(className) : className;

            final String firstColumn = indentation + formattedIndexString + " " + formattedClassName;

            table.addRow(firstColumn);

            if (useLabels) {
                if (node instanceof final Labeled labeled) {
                    table.addToRow(labeled.getLabel());
                } else {
                    table.addToRow("");
                }
            }

            if (useFullRoute) {
                final String routeString = MerkleRouteUtils.merkleRouteToPathFormat(route);
                final String formattedRouteString = useColors ? BRIGHT_RED.apply(routeString) : routeString;
                table.addToRow(formattedRouteString);
            }

            if (useHashes || useMnemonics) {
                final Hash hash = node == null ? Cryptography.NULL_HASH : node.getHash();
                final String hashString;
                if (hash == null) {
                    hashString = "null";
                } else if (hashLength == -1) {
                    hashString = hash.toString();
                } else {
                    hashString = hash.toString().substring(0, hashLength);
                }

                if (useMnemonics) {
                    final String mnemonic = hash == null ? "" : Mnemonics.generateMnemonic(hash);
                    final String formattedMnemonic = useColors ? WHITE.apply(mnemonic) : mnemonic;
                    table.addToRow(formattedMnemonic);
                }

                if (useHashes) {
                    final String formattedHashString = useColors ? GRAY.apply(hashString) : hashString;
                    table.addToRow(formattedHashString);
                }
            }
        });

        table.render(sb);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return render();
    }
}
