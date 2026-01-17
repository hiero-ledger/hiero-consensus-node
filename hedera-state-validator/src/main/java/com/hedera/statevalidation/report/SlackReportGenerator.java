// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.report;

import static com.hedera.statevalidation.util.ConfigUtils.JOB_URL;
import static com.hedera.statevalidation.util.ConfigUtils.NET_NAME;
import static com.hedera.statevalidation.util.ConfigUtils.NODE_DESCRIPTION;
import static com.hedera.statevalidation.util.ConfigUtils.ROUND;
import static com.hedera.statevalidation.util.ConfigUtils.SLACK_TAGS;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class SlackReportGenerator implements AfterTestExecutionCallback {

    private static final String REPORT_FILE_PATH = "slack_report.json";
    private static final List<TestResult> testResults;
    private static final ObjectMapper mapper;

    static {
        testResults = new ArrayList<>();
        mapper = new ObjectMapper();
        Runtime.getRuntime().addShutdownHook(new Thread(SlackReportGenerator::generateReport));
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        if (context.getExecutionException().isPresent()) {
            testResults.add(new TestResult(
                    context.getDisplayName(),
                    context.getExecutionException().get().getMessage()));
        }
    }

    public static void generateReport() {
        try {

            ArrayNode blocksArray = mapper.createArrayNode();
            addHeader(blocksArray);
            addTestResults(blocksArray);
            addTags(blocksArray);
            ObjectNode attachment = mapper.createObjectNode();
            attachment.put("color", "#ff0000");
            attachment.set("blocks", blocksArray);
            ArrayNode attachmentArray = mapper.createArrayNode();
            attachmentArray.add(attachment);
            ObjectNode reportNode = mapper.createObjectNode();
            reportNode.set("attachments", attachmentArray);
            File previousReport = new File(REPORT_FILE_PATH);
            if (previousReport.exists()) {
                mapper.readTree(previousReport).get("attachments").forEach(attachmentArray::add);
            }
            try (FileWriter fileWriter = new FileWriter(REPORT_FILE_PATH)) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(fileWriter, reportNode);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void addHeader(ArrayNode blockArrayNode) {
        ObjectNode blockNode = mapper.createObjectNode();
        blockNode.put("type", "header");
        ObjectNode textNode = mapper.createObjectNode();
        textNode.put("type", "plain_text");
        textNode.put(
                "text",
                String.format(":boom: %s State Validation failed for %s, round %s", NET_NAME, NODE_DESCRIPTION, ROUND));
        textNode.put("emoji", true);
        blockNode.set("text", textNode);

        blockArrayNode.add(blockNode);
    }

    private static void addTestResults(ArrayNode blockArrayNode) {
        for (TestResult testResult : testResults) {
            ObjectNode blockNode = mapper.createObjectNode();
            blockNode.put("type", "section");
            ObjectNode textNode = mapper.createObjectNode();
            textNode.put("type", "mrkdwn");
            textNode.put("text", String.format("*%s* %s", testResult.testName, testResult.errorMessage));
            blockNode.set("text", textNode);
            blockArrayNode.add(blockNode);
        }
    }

    private static void addTags(ArrayNode blockArrayNode) {
        for (String slackTag : SLACK_TAGS.split(",")) {
            ObjectNode blockNode = mapper.createObjectNode();
            blockNode.put("type", "section");
            ObjectNode textNode = mapper.createObjectNode();
            textNode.put("type", "mrkdwn");
            textNode.put("text", String.format("person to notify - %s . See <%s|job details here>", slackTag, JOB_URL));
            blockNode.set("text", textNode);
            blockArrayNode.add(blockNode);
        }
    }

    public record TestResult(String testName, String errorMessage) {}
}
