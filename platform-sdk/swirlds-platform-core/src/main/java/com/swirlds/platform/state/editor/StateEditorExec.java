// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.editor;

import static com.swirlds.common.formatting.TextEffect.BRIGHT_CYAN;
import static com.swirlds.platform.state.editor.StateEditorUtils.formatNode;
import static com.swirlds.platform.state.editor.StateEditorUtils.formatNodeType;

import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.route.MerkleRouteIterator;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.state.signed.ReservedSignedState;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.crypto.Hashable;
import picocli.CommandLine;

@CommandLine.Command(name = "exec", mixinStandardHelpOptions = true, description = "Run a function on a node.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorExec extends StateEditorOperation {
    private static final Logger logger = LogManager.getLogger(StateEditorExec.class);

    private String function = "";
    private String path = "";

    private static final ClassLoader classLoader = StateEditorExec.class.getClassLoader();

    @CommandLine.Parameters(
            index = "0",
            description = "The fully qualified name of the function to run. Should be a static method "
                    + "that implements the interface Function<MerkleNode, MerkleNode>. The return value, "
                    + "if different than the original, will replace the original node. "
                    + "e.g. com.swirlds.foo.bar.MyClass.myFunction")
    private void setFunction(final String function) {
        this.function = function;
    }

    @CommandLine.Parameters(index = "1", arity = "0..1", description = "The path of the node to run the function on.")
    private void setPath(final String path) {
        this.path = path;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        try {
            final MerkleNode node = getStateEditor().getRelativeNode(path);
            final String className = function.substring(0, function.lastIndexOf('.'));
            final String methodName = function.substring(function.lastIndexOf('.') + 1);

            final Class<?> clazz = classLoader.loadClass(className);
            final Method method = clazz.getMethod(methodName, MerkleNode.class);

            if (logger.isInfoEnabled(LogMarker.CLI.getMarker())) {
                logger.info(
                        LogMarker.CLI.getMarker(), "Applying {} to {}", BRIGHT_CYAN.apply(function), formatNode(node));
            }
            final MerkleNode result = (MerkleNode) method.invoke(null, node);

            if (result != node) {
                if (logger.isInfoEnabled(LogMarker.CLI.getMarker())) {
                    logger.info(
                            LogMarker.CLI.getMarker(),
                            "Replacing {} with node {} returned by {}()",
                            formatNode(node),
                            formatNodeType(result),
                            methodName);
                }

                final StateEditor.ParentInfo parentInfo = getStateEditor().getParentInfo(path);
                parentInfo.parent().setChild(parentInfo.indexInParent(), result);

                // Invalidate hashes in path down from root
                try (final ReservedSignedState reservedSignedState =
                        getStateEditor().getState("StateEditorExec.run()")) {
                    new MerkleRouteIterator(
                                    reservedSignedState.get().getState().getRoot(),
                                    parentInfo.parent().getRoute())
                            .forEachRemaining(Hashable::invalidateHash);
                }
            }
        } catch (ClassNotFoundException
                | NoSuchMethodException
                | IllegalAccessException
                | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}
