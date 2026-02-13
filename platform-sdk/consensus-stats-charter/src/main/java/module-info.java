// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.stats.charter {
    requires info.picocli;
    requires java.desktop;
    requires org.jfree.jfreechart;
    requires org.jfree.pdf;
    requires static com.github.spotbugs.annotations;

    opens org.hiero.consensus.stats.charter to
            info.picocli;
}
