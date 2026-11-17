// SPDX-License-Identifier: Apache-2.0

// Fails the build if a unit test of the module never executes a call site of `addExtraFee`. The enabled assertion in
// `ServiceFeeCalculator.addExtraFee` only checks an extra when its call site runs, so an unexecuted call site could
// hide an extra that is not declared in `requiredExtras()` and would only fail at runtime.

val jacocoXmlReport = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml")

tasks.named<JacocoReport>("jacocoTestReport") {
    reports.xml.required = true
    reports.xml.outputLocation = jacocoXmlReport
}

val verifyExtraFeeCallSiteCoverage =
    tasks.register("verifyExtraFeeCallSiteCoverage") {
        group = "verification"
        description = "Fails if a call site of addExtraFee is not executed by the unit tests of this module."
        // capture only locals: the task action must not refer to the script or the project (configuration cache)
        val sourceRoot = layout.projectDirectory.dir("src/main/java")
        val reportFile = jacocoXmlReport
        val projectName = project.name
        inputs.file(reportFile)
        inputs.dir(sourceRoot)
        dependsOn("jacocoTestReport")
        doLast {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            // the JaCoCo report refers to a DTD that is not available offline
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            val report = factory.newDocumentBuilder().parse(reportFile.get().asFile)

            // package path + "/" + source file name -> lines executed at least once
            val executedLines = mutableMapOf<String, Set<Int>>()
            val packages = report.getElementsByTagName("package")
            for (p in 0 until packages.length) {
                val pkg = packages.item(p) as org.w3c.dom.Element
                val sourceFiles = pkg.getElementsByTagName("sourcefile")
                for (f in 0 until sourceFiles.length) {
                    val sourceFile = sourceFiles.item(f) as org.w3c.dom.Element
                    val lines = sourceFile.getElementsByTagName("line")
                    executedLines["${pkg.getAttribute("name")}/${sourceFile.getAttribute("name")}"] =
                        (0 until lines.length)
                            .map { lines.item(it) as org.w3c.dom.Element }
                            .filter { it.getAttribute("ci").toInt() > 0 }
                            .map { it.getAttribute("nr").toInt() }
                            .toSet()
                }
            }

            val callSite = Regex("""\baddExtraFee\(""")
            val declaration = Regex("""\bvoid\s+addExtraFee\(""")
            val unexecuted = mutableListOf<String>()
            sourceRoot.asFile
                .walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .forEach { file ->
                    val key = file.relativeTo(sourceRoot.asFile).invariantSeparatorsPath
                    val executed = executedLines[key] ?: emptySet()
                    file.readLines().forEachIndexed { index, line ->
                        if (callSite.containsMatchIn(line) && !declaration.containsMatchIn(line)) {
                            if ((index + 1) !in executed) {
                                unexecuted += "$key:${index + 1}  ${line.trim()}"
                            }
                        }
                    }
                }
            if (unexecuted.isNotEmpty()) {
                throw GradleException(
                    "addExtraFee call sites not executed by the unit tests of $projectName:\n" +
                        unexecuted.joinToString("\n") { "  $it" }
                )
            }
        }
    }

tasks.named("check") { dependsOn(verifyExtraFeeCallSiteCoverage) }
