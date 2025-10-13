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
     *
     * @param root The JSON parsed object tree to parse data from
     * @return Parsed HashObject model object or null if data input was null or empty
     * @throws ParseException If parsing fails
     */
    public @NonNull IssState parse(
            @Nullable final JSONParser.ObjContext root, final boolean strictMode, final int maxDepth)
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
