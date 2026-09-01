// SPDX-License-Identifier: Apache-2.0
// Deterministic freshness/drift checker for the curated consensus-layer knowledge base under
// platform-sdk/docs/consensus-layer/. The engine never calls a model and performs no network I/O;
// the same checkout in yields the same findings out.
module org.hiero.consensus.kbfreshness {
    exports org.hiero.consensus.kbfreshness.cli;

    // picocli reflects over the @Command class and its @Option fields.
    opens org.hiero.consensus.kbfreshness.cli to
            info.picocli;

    // picocli: declarative CLI parsing for the entry point.
    requires info.picocli;
    // javax.tools: obtain the system Java compiler for parse-only source analysis.
    requires java.compiler;
    // com.sun.source.tree / com.sun.source.util: the Compiler Tree API used to parse (not compile)
    // cited source files and resolve declared symbols without a full build.
    requires jdk.compiler;
}
