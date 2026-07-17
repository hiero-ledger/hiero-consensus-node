{
  "attachments": [
    {
      "color": "#FF8C00",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": {{ printf ":warning: XTS - eXtended Test Suite Environment Failure Report (%s)" (getenv "XTS_INFO" | required "XTS_INFO must be set") | data.ToJSON }},
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
            "text": "*Environment issue detected.*"
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
            "text": "*Workflow run URL*:"
          },
          "fields": [
            {
              "type": "mrkdwn",
              "text": {{ printf "*Run attempt*: %s" (getenv "RUN_ATTEMPT") | data.ToJSON }}
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
