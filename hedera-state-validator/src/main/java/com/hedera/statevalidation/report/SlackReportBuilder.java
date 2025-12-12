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
import java.util.List;

/**
 * Utility class for building and writing Slack report JSON files.
 */
public final class SlackReportBuilder {

    private static final String DEFAULT_REPORT_FILE_PATH = "slack_report.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    private SlackReportBuilder() {}

    /**
     * Represents a validation failure with a name and error message.
     */
    public record ValidationFailure(String name, String errorMessage) {}

    /**
     * Generates a Slack report from validation failures.
     */
    public static void generateReport(List<ValidationFailure> failures) {
        generateReport(failures, DEFAULT_REPORT_FILE_PATH);
    }

    /**
     * Generates a Slack report from validation failures to the specified path.
     */
    public static void generateReport(List<ValidationFailure> failures, String reportFilePath) {
        if (failures.isEmpty()) {
            return;
        }

        try {
            ArrayNode blocksArray = mapper.createArrayNode();
            addHeader(blocksArray);
            addFailures(blocksArray, failures);
            addTags(blocksArray);

            ObjectNode attachment = mapper.createObjectNode();
            attachment.put("color", "#ff0000");
            attachment.set("blocks", blocksArray);

            ArrayNode attachmentArray = mapper.createArrayNode();
            attachmentArray.add(attachment);

            ObjectNode reportNode = mapper.createObjectNode();
            reportNode.set("attachments", attachmentArray);

            File previousReport = new File(reportFilePath);
            if (previousReport.exists()) {
                mapper.readTree(previousReport).get("attachments").forEach(attachmentArray::add);
            }

            try (FileWriter fileWriter = new FileWriter(reportFilePath)) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(fileWriter, reportNode);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate Slack report", e);
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

    private static void addFailures(ArrayNode blockArrayNode, List<ValidationFailure> failures) {
        for (ValidationFailure failure : failures) {
            ObjectNode blockNode = mapper.createObjectNode();
            blockNode.put("type", "section");
            ObjectNode textNode = mapper.createObjectNode();
            textNode.put("type", "mrkdwn");
            textNode.put("text", String.format("*%s* %s", failure.name(), failure.errorMessage()));
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
}
