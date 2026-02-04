---

scope: clpr
audience: engineering
status: draft
last_updated: 2026-01-28
------------------------

# CLPR APIs

This directory captures the evolving API contracts and message shapes.

## Structure

- app/ : Application-facing API methods and messages
- connector/ : Connector-facing API methods and messages
- msg-queue/ : Message queue API methods and messages

Each method or message should have its own file once details stabilize.

Note: The messaging-to-middleware delivery API includes the messaging-layer
message id (`ClprMsgId`) alongside `ClprMessage`.
