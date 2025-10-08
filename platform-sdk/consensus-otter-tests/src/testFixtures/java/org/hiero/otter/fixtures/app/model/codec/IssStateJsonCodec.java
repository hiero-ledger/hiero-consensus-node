// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.model.codec;

import static com.hedera.pbj.runtime.JsonTools.INDENT;
import static com.hedera.pbj.runtime.JsonTools.field;
import static com.hedera.pbj.runtime.JsonTools.parseLong;

import com.hedera.pbj.runtime.JsonCodec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.UnknownFieldException;
import com.hedera.pbj.runtime.jsonparser.JSONParser;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import org.hiero.otter.fixtures.app.model.IssState;

/**
 * JSON Codec for IssState model object. Generated based on protobuf schema.
 */
public final class IssStateJsonCodec implements JsonCodec<IssState> {

    /**
     * Empty constructor
     */
    public IssStateJsonCodec() {
        // no-op
    }

    /**
     * Parses a HashObject object from JSON parse tree for object JSONParser.ObjContext.
     * Throws an UnknownFieldException wrapped in a ParseException if in strict mode ONLY.
     * <p>
     * The {@code maxSize} specifies a custom value for the default `Codec.DEFAULT_MAX_SIZE` limit. IMPORTANT:
     * specifying a value larger than the default one can put the application at risk because a maliciously-crafted
     * payload can cause the parser to allocate too much memory which can result in OutOfMemory and/or crashes.
     * It's important to carefully estimate the maximum size limit that a particular protobuf model type should support,
     * and then pass that value as a parameter. Note that the estimated limit should apply to the **type** as a whole,
     * rather than to individual instances of the model. In other words, this value should be a constant, or a config
     * value that is controlled by the application, rather than come from the input that the application reads.
     * When in doubt, use the other overloaded versions of this method that use the default `Codec.DEFAULT_MAX_SIZE`.
     *
     * @param root The JSON parsed object tree to parse data from
     * @param maxSize a ParseException will be thrown if the size of a delimited field exceeds the limit
     * @return Parsed HashObject model object or null if data input was null or empty
     * @throws ParseException If parsing fails
     */
    public @NonNull IssState parse(
            @Nullable final JSONParser.ObjContext root, final boolean strictMode, final int maxDepth, final int maxSize)
            throws ParseException {
        if (maxDepth < 0) {
            throw new ParseException("Reached maximum allowed depth of nested messages");
        }
        try {
            // -- TEMP STATE FIELDS --------------------------------------
            long temp_issState = 0;

            // -- EXTRACT VALUES FROM PARSE TREE ---------------------------------------------

            for (JSONParser.PairContext kvPair : root.pair()) {
                switch (kvPair.STRING().getText()) {
                    case "issState" /* [1] */:
                        temp_issState = parseLong(kvPair.value());
                        break;

                    default: {
                        if (strictMode) {
                            // Since we are parsing is strict mode, this is an exceptional condition.
                            throw new UnknownFieldException(kvPair.STRING().getText());
                        }
                    }
                }
            }

            return new IssState(temp_issState);
        } catch (Exception ex) {
            throw new ParseException(ex);
        }
    }

    /**
     * Returns JSON string representing an item.
     *
     * @param data      The item to convert. Must not be null.
     * @param indent    The indent to use for pretty printing
     * @param inline    When true the output will start with indent end with a new line otherwise
     *                        it will just be the object "{...}"
     */
    @Override
    public String toJSON(@NonNull IssState data, String indent, boolean inline) {
        StringBuilder sb = new StringBuilder();
        // start
        sb.append(inline ? "{\n" : indent + "{\n");
        final String childIndent = indent + INDENT;
        // collect field lines
        final List<String> fieldLines = new ArrayList<>();
        // [1] - issState
        if (data.issState() != 0) fieldLines.add(field("issState", data.issState()));

        // write field lines
        if (!fieldLines.isEmpty()) {
            sb.append(childIndent);
            sb.append(String.join(",\n" + childIndent, fieldLines));
            sb.append("\n");
        }
        // end
        sb.append(indent + "}");
        return sb.toString();
    }
}
