---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-04
------------------------

# Application layer (target)

## Responsibilities

- Build application-specific payloads for ClprApplicationMessage.
- Handle ClprApplicationResponse and map it to application responses.
- Manage application-level correlation and error handling.
- Treat ClprMessage and ClprMessageResponse as middleware internals.
- Select a connector; this choice fixes the remote ledger and remote connector.

## Interop expectations

- Applications should not depend on CLPR message ids.
- Applications may include their own nonce or correlation data in
  the message payload and expect it in responses.
- Applications receive a ClprSendMessageStatus with a middleware-assigned
  ClprAppMsgId for each send attempt.
- Applications may supply a maximum charge amount (with unit) for remote
  execution; the effective limit is the lower of the application and connector
  limits.
- Responses are delivered with the ClprAppMsgId as a side parameter for
  correlation.
- Application-level error propagation should be explicit so assets
  or state changes can be safely rolled back or released on failure.
