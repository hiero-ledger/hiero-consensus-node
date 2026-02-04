---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-02-04
------------------------

# CLPR architecture (target)

## Components and boundaries

CLPR is organized as four cooperating layers:

1. Messaging layer
   - Owns connections, queue state, bundle formation, and state proofs.
   - Verifies inbound bundles and produces verified messages.
2. Middleware layer
   - Defines ClprMessage and ClprMessageResponse semantics.
   - Routes messages to application handlers.
   - Provides App API, Connector API, Msg Queue API, and message delivery boundaries.
3. Connector layer
   - Authorizes outbound messages.
   - Commits funds for remote handling and local response handling.
   - Receives notifications for inbound messages and responses.
4. Application layer
   - Builds application-specific payloads and responses.
   - Does not depend on CLPR message ids.

## Call flow summary

Forward path (source to destination):
1. Application calls middleware App API with a ClprApplicationMessage (includes connector_id).
2. Middleware constructs a ClprMessageDraft, validates connector registration/pairing state, and calls the source connector authorize hook.
3. Source connector returns a ClprConnectorMessage with approval/denial, max_charge, and optional metadata; destination connector id is provided by middleware in the draft for verification.
4. If denied, middleware returns a failed status to the application and does not enqueue.
5. If approved, middleware finalizes ClprMessage (adds connector_message) and enqueues via Msg Queue API; messaging assigns message id.
6. Messaging bundles messages and transports them with state proofs.
7. Destination messaging verifies proofs and delivers verified ClprMessage plus
the assigned message id to destination middleware.
8. Destination middleware checks connector presence/funds; if missing/underfunded it skips application execution and prepares a failure response.
9. If allowed, destination middleware routes to the destination application for execution.

Return path (destination to source):
1. Destination application returns a ClprApplicationResponse (if executed).
2. If the destination connector exists, destination middleware calls the connector handle hook with the ClprMessage, ClprMessageResponse, and billing info to produce a ClprConnectorResponse; otherwise it uses an empty connector response.
3. Middleware constructs a ClprMessageResponse including application_response, connector_response, and middleware_response (success or failure reason).
4. Middleware enqueues response; messaging transports it back.
5. Source middleware delivers the ClprMessage, ClprMessageResponse, and ClprConnectorResponse to the source connector.
6. Source middleware delivers the application response to the source application; if middleware_response indicates failure, it surfaces a failure ClprApplicationResponse.

## Trust and finality

- CLPR inherits the finality model of each ledger.
- Only one honest endpoint per ledger is required for correctness.
- Messages are accepted only with valid state proofs.
- Every accepted message produces a deterministic response.
