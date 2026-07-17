{
  "attachments": [
    {
      "color": "#FF0000",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": {{ printf ":x: XTS - eXtended Test Suite Test Failure Report (%s) Failed" (getenv "XTS_INFO" | required "XTS_INFO must be set") | data.ToJSON }},
            "emoji": true
          }
        },
        {
          "type": "divider"
        },
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": "*XTS test failure. See status below.*"
          },
          "fields": [
            {
              "type": "mrkdwn",
              "text": {{ printf "*Fetch XTS Candidate Tag*: %s" (getenv "FETCH_XTS_CANDIDATE_RESULT") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*XTS Execution*: %s" (getenv "XTS_EXECUTION_RESULT") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*Tag as XTS-Passing*: %s" (getenv "TAG_FOR_PROMOTION_RESULT") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*Failing Test(s)*: %s" (getenv "FAILED_TESTS" | required "FAILED_TESTS must be set") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*Run attempt*: %s" (getenv "RUN_ATTEMPT") | data.ToJSON }}
            }
          ]
        },
        {
          "type": "divider"
        },
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": "*Workflow and Commit Information*"
          },
          "fields": [
            {
              "type": "mrkdwn",
              "text": "*Source Commit*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "<%s>" (getenv "COMMIT_URL" | required "COMMIT_URL must be set") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": "*Commit author*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ getenv "COMMIT_AUTHOR" | required "COMMIT_AUTHOR must be set" | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": "*Slack user*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ getenv "SLACK_USER_ID" | default "N/A" | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": "*Workflow run ID*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ getenv "WORKFLOW_RUN_ID" | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": "*Workflow run URL*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "<%s>" (getenv "WORKFLOW_RUN_URL" | required "WORKFLOW_RUN_URL must be set") | data.ToJSON }}
            }
          ]
        },
        {
          "type": "divider"
        },
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": "*Commit List*:"
          },
          "fields": [
            {
              "type": "mrkdwn",
              "text": {{ getenv "COMMIT_LIST" | required "COMMIT_LIST must be set" | data.ToJSON }}
            }
          ]
        },
        {
          "type": "divider"
        },
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": "*CITR Configuration*"
          },
          "fields": [
            {
              "type": "mrkdwn",
              "text": {{ printf "*Solo*: %s" (getenv "CITR_SOLO_VERSION") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*TCK*: %s" (getenv "CITR_TCK_VERSION") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*JS SDK*: %s" (getenv "CITR_JS_SDK_VERSION") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*Mirror Node*: %s" (getenv "CITR_MIRROR_NODE_VERSION") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*Block Node Release*: %s" (getenv "CITR_BN_RELEASE_VERSION") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*Block Node Mirror*: %s" (getenv "CITR_BN_MIRROR_NODE_VERSION") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*JSON-RPC Relay*: %s" (getenv "CITR_JSON_RPC_RELAY_VERSION") | data.ToJSON }}
            }
          ]
        }
      ]
    }
  ]
}
