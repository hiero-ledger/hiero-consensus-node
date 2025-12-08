// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.model;

import static com.hedera.pbj.runtime.ProtoWriterTools.sizeOfLong;
import static com.hedera.pbj.runtime.ProtoWriterTools.sizeOfVarInt32;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.UnknownField;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.List;
import org.hiero.otter.fixtures.app.model.codec.EntityIdGeneratorProtoCodec;
import org.hiero.otter.fixtures.app.model.schema.EntityIdGeneratorSchema;

/**
 * EntityIdGenerator
 */
public final class EntityIdGenerator {

    /** Protobuf codec for reading and writing in protobuf format */
    public static final Codec<EntityIdGenerator> PROTOBUF = new EntityIdGeneratorProtoCodec();

    /** Default instance with all fields set to default values */
    public static final EntityIdGenerator DEFAULT = newBuilder().build();

    /** Field <b>(1)</b>  */
    private final long nextId;
    /** Computed hash code, manual input ignored. */
    private int $hashCode = -1;
    /** Computed protobuf encoded size, manual input ignored. */
    private int $protobufEncodedSize = -1;

    private final List<UnknownField> $unknownFields;

    /**
     * Create a pre-populated EntityIdGenerator.
     *
     * @param nextId <b>(1)</b>
     */
    public EntityIdGenerator(long nextId) {
        this.$unknownFields = null;
        this.nextId = nextId;
    }

    /**
     * Create a pre-populated EntityIdGenerator.
     *
     * @param nextId <b>(1)</b>
     */
    public EntityIdGenerator(long nextId, final List<UnknownField> $unknownFields) {
        this.$unknownFields = $unknownFields == null ? null : Collections.unmodifiableList($unknownFields);
        this.nextId = nextId;
    }

    /**
     * Get field <b>(1)</b>
     *
     * @return the value of the nextId field
     */
    public long nextId() {
        return nextId;
    }

    /**
     * Get an unmodifiable list of all unknown fields parsed from the original data, i.e. the fields
     * that are unknown to the .proto model which generated this Java model class. The fields are sorted
     * by their field numbers in an increasing order.
     * <p>
     * Note that by default, PBJ Codec discards unknown fields for performance reasons.
     * The parse() method has to be invoked with `parseUnknownFields = true` in order to populate the
     * unknown fields.
     * <p>
     * Also note that there may be multiple `UnknownField` items with the same field number
     * in case a repeated field uses the unpacked wire format. It's up to the application
     * to interpret these unknown fields correctly if necessary.
     * <p>
     * If the parsing of unknown fields was enabled when this model instance was parsed originally and
     * the unknown fields were present, then a subsequent `Codec.write()` call will persist all the parsed
     * unknown fields.
     *
     * @return a (potentially empty) list of unknown fields
     */
    public @NonNull List<UnknownField> getUnknownFields() {
        return $unknownFields == null ? Collections.EMPTY_LIST : $unknownFields;
    }

    /**
     * Get number of bytes when serializing the object to protobuf binary.
     *
     * @return The length in bytes in protobuf encoding
     */
    public int protobufSize() {
        // The $protobufEncodedSize field is subject to a benign data race, making it crucial to ensure that any
        // observable result of the calculation in this method stays correct under any possible read of this
        // field. Necessary restrictions to allow this to be correct without explicit memory fences or similar
        // concurrency primitives is that we can ever only write to this field for a given Model object
        // instance, and that the computation is idempotent and derived from immutable state.
        // This is the same trick used in java.lang.String.hashCode() to avoid synchronization.

        if ($protobufEncodedSize == -1) {
            int _size = 0;
            // [1] - nextId
            _size += sizeOfLong(EntityIdGeneratorSchema.NEXT_ID, nextId, true);

            if ($unknownFields != null) {
                for (int i = 0; i < $unknownFields.size(); i++) {
                    final UnknownField uf = $unknownFields.get(i);
                    _size += sizeOfVarInt32((uf.field() << ProtoParserTools.TAG_FIELD_OFFSET)
                            | uf.wireType().ordinal());
                    _size += Math.toIntExact(uf.bytes().length());
                }
            }

            $protobufEncodedSize = _size;
        }
        return $protobufEncodedSize;
    }

    /**
     * Override the default hashCode method for to make hashCode better distributed and follows protobuf rules
     * for default values. This is important for backward compatibility. This also lazy computes and caches the
     * hashCode for future calls. It is designed to be thread safe.
     */
    @Override
    public int hashCode() {
        // The $hashCode field is subject to a benign data race, making it crucial to ensure that any
        // observable result of the calculation in this method stays correct under any possible read of this
        // field. Necessary restrictions to allow this to be correct without explicit memory fences or similar
        // concurrency primitives is that we can ever only write to this field for a given Model object
        // instance, and that the computation is idempotent and derived from immutable state.
        // This is the same trick used in java.lang.String.hashCode() to avoid synchronization.

        if ($hashCode == -1) {
            int result = 1;
            if (nextId != DEFAULT.nextId) {
                result = 31 * result + Long.hashCode(nextId);
            }
            if ($unknownFields != null) {
                for (int i = 0; i < $unknownFields.size(); i++) {
                    result = 31 * result + $unknownFields.get(i).hashCode();
                }
            }
            long hashCode = result;
            // Shifts: 30, 27, 16, 20, 5, 18, 10, 24, 30
            hashCode += hashCode << 30;
            hashCode ^= hashCode >>> 27;
            hashCode += hashCode << 16;
            hashCode ^= hashCode >>> 20;
            hashCode += hashCode << 5;
            hashCode ^= hashCode >>> 18;
            hashCode += hashCode << 10;
            hashCode ^= hashCode >>> 24;
            hashCode += hashCode << 30;

            $hashCode = (int) hashCode;
        }
        return $hashCode;
    }

    /**
     * Override the default equals method for
     */
    @Override
    public boolean equals(Object that) {
        if (that == null || this.getClass() != that.getClass()) {
            return false;
        }
        EntityIdGenerator thatObj = (EntityIdGenerator) that;
        if ($hashCode != -1 && thatObj.$hashCode != -1 && $hashCode != thatObj.$hashCode) {
            return false;
        }
        if (nextId != thatObj.nextId) {
            return false;
        }
        // Treat null and empty lists as equal
        if ($unknownFields != null && !$unknownFields.isEmpty()) {
            if (thatObj.$unknownFields == null || $unknownFields.size() != thatObj.$unknownFields.size()) {
                return false;
            }
            // Both are non-null and non-empty lists of the same size, and both are sorted in the same order
            // (the sorting is the parser responsibility.)
            // So the List.equals() is the most optimal way to compare them here.
            // It will simply call UnknownField.equals() for each element at the same index in both the lists:
            if (!$unknownFields.equals(thatObj.$unknownFields)) {
                return false;
            }
        } else if (thatObj.$unknownFields != null && !thatObj.$unknownFields.isEmpty()) {
            return false;
        }
        return true;
    }

    /**
     * Override the default toString method for EntityIdGenerator to match the format of a Java record.
     */
    @Override
    public String toString() {
        String $ufstr = null;
        if ($unknownFields != null && !$unknownFields.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < $unknownFields.size(); i++) {
                if (i > 0) sb.append(", ");
                $unknownFields.get(i).printToString(sb);
            }
            $ufstr = sb.toString();
        }
        return "EntityIdGenerator[" + "nextId=" + nextId + ($ufstr == null ? "" : (", " + $ufstr)) + "]";
    }

    /**
     * Return a builder for building a copy of this model object. It will be pre-populated with all the data from this
     * model object.
     *
     * @return a pre-populated builder
     */
    public Builder copyBuilder() {
        return new Builder(nextId, $unknownFields);
    }

    /**
     * Return a new builder for building a model object. This is just a shortcut for <code>new Model.Builder()</code>.
     *
     * @return a new builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Builder class for easy creation, ideal for clean code where performance is not critical. In critical performance
     * paths use the constructor directly.
     */
    public static final class Builder {
        private long nextId = 0;
        private final List<UnknownField> $unknownFields;

        /**
         * Create an empty builder
         */
        public Builder() {
            $unknownFields = null;
        }

        /**
         * Create a pre-populated Builder.
         *
         * @param nextId <b>(1)</b>
         */
        public Builder(long nextId) {
            this.$unknownFields = null;
            this.nextId = nextId;
        }

        /**
         * Create a pre-populated Builder.
         *
         * @param nextId <b>(1)</b>
         */
        public Builder(long nextId, final List<UnknownField> $unknownFields) {
            this.$unknownFields = $unknownFields == null ? null : Collections.unmodifiableList($unknownFields);
            this.nextId = nextId;
        }

        /**
         * Build a new model record with data set on builder
         *
         * @return new model record with data set
         */
        public EntityIdGenerator build() {
            return new EntityIdGenerator(nextId);
        }

        /**
         * <b>(1)</b>
         *
         * @param nextId value to set
         * @return builder to continue building with
         */
        public Builder nextId(long nextId) {
            this.nextId = nextId;
            return this;
        }
    }
}
