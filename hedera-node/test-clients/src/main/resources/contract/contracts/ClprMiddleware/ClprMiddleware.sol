// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.24;

import { ClprTypes } from "../types/ClprTypes.sol";
import { IClprMiddleware } from "../interfaces/IClprMiddleware.sol";
import { IClprQueue } from "../interfaces/IClprQueue.sol";
import { IClprDestinationApplication, IClprSourceApplication } from "../interfaces/IClprApplication.sol";
import { IClprConnector } from "../interfaces/IClprConnector.sol";

/// @title CLPR Middleware
/// @author Hashgraph
/// @notice Reference middleware implementation for the CLPR MVP connector workflow.
/// @dev This contract is a Solidity reference implementation for middleware semantics and is expected
///      to remain behaviorally aligned with the CLPR requirements docs:
///      - `requirements/middleware-apis-and-semantics.md`
///      - `requirements/messaging-message-formats.md`
///      - `requirements/connectors-economics-and-behavior.md`
///
///      It models:
///      - connector registration + pairing (1:1 remote connector association),
///      - source connector authorization (approve/deny + max charge),
///      - destination connector funds checks + reimbursement,
///      - balance report propagation (source->dest and dest->source), and
///      - out-of-funds pre-enqueue rejection based on the latest known remote status.
contract ClprMiddleware is IClprMiddleware {
    uint8 private constant ROUTE_VERSION = 1;

    /// @notice Stored routing context for a message awaiting response delivery.
    struct PendingOutboundMessage {
        address sourceApplication;
        uint64 appMsgId;
        bytes32 sourceConnectorId;
        bytes32 destinationConnectorId;
        uint256 maxChargeCommitment;
        bool exists;
    }

    /// @notice Local connector registration state (protocol handler replacement for the Solidity prototype).
    struct ConnectorRegistration {
        address connectorAddress;
        bytes32 remoteLedgerId;
        bytes32 expectedRemoteConnectorId;
        address remoteMiddleware;
        address admin;
        bool enabled;
        bool exists;
    }

    /// @notice Latest known remote connector funding and policy status.
    /// @dev Keyed by the destination connector id (as used on the remote ledger).
    struct RemoteConnectorStatus {
        uint256 availableBalance;
        uint256 safetyThreshold;
        uint256 minimumCharge;
        uint256 maximumCharge;
        string unit;
        bool known;
        bool unavailable;
    }

    /// @notice Thrown when a queue-only entrypoint is called by a non-queue address.
    error QueueOnly();

    /// @notice Thrown when middleware is constructed with an invalid queue address.
    error InvalidQueue();

    /// @notice Thrown when an application is not registered locally.
    error ApplicationNotRegistered();

    /// @notice Thrown when an inbound message targets an invalid recipient.
    error InvalidRecipient();

    /// @notice Thrown when a response references an unknown message id.
    error UnknownMessage();

    /// @notice Thrown when attempting to register a connector with an invalid id.
    error InvalidConnectorId();

    /// @notice Thrown when attempting an admin-only connector operation.
    error AdminOnly();

    /// @notice Thrown when attempting to register a connector with an invalid admin.
    error InvalidAdmin();

    /// @notice Thrown when attempting to configure an invalid remote middleware address.
    error InvalidRemoteMiddleware();

    /// @notice Thrown when a non-owner attempts an owner-only operation.
    error OwnerOnly();

    /// @notice Thrown when a request route header cannot be decoded or validated.
    error InvalidRouteHeader();

    /// @notice Address of the messaging-layer queue mock used for enqueue and callbacks.
    address public immutable queue;

    /// @notice Deployer-owned admin role for middleware-level configuration.
    address public immutable owner;

    /// @notice Opaque ledger id (used only for connector identity derivation in the spec).
    bytes32 public immutable override ledgerId;

    /// @notice Optional trusted callback caller in addition to the queue contract.
    address public trustedCallbackCaller;

    /// @notice Local allow-list of applications this middleware can route for.
    mapping(address => bool) public localApplications;

    /// @notice Local connector registry keyed by local connector id.
    mapping(bytes32 => ConnectorRegistration) public connectors;

    /// @notice Next per-application message id (ClprAppMsgId), monotonically increasing.
    mapping(address => uint64) public nextAppMsgId;

    /// @notice Pending outbound messages keyed by messaging-layer `ClprMsgId`.
    mapping(uint64 => PendingOutboundMessage) public pendingByMessageId;

    /// @notice Source-tracked outstanding commitments for a destination connector.
    /// @dev Sum of per-message max-charge commitments for messages enqueued locally and awaiting remote confirmation.
    mapping(bytes32 => uint256) public outstandingCommitmentsByDestinationConnector;

    /// @notice Latest known remote connector funding/policy status keyed by destination connector id.
    mapping(bytes32 => RemoteConnectorStatus) public remoteStatusByDestinationConnector;

    /// @notice Penalty counter for a source connector id (placeholder for slashing policy).
    mapping(bytes32 => uint64) public penaltyCountBySourceConnector;

    /// @notice Emitted when an application is registered for local routing.
    event ApplicationRegistered(address indexed application);

    /// @notice Emitted when a connector is registered with this middleware.
    event ConnectorRegistered(
        bytes32 indexed connectorId,
        address indexed connectorAddress,
        bytes32 indexed expectedRemoteConnectorId,
        bytes32 remoteLedgerId,
        address admin
    );

    /// @notice Emitted when a connector is disabled (new sends rejected).
    event ConnectorDisabled(bytes32 indexed connectorId);

    /// @notice Emitted when a connector registration is deleted.
    event ConnectorDeleted(bytes32 indexed connectorId);

    /// @notice Emitted when a connector's paired remote middleware address is configured.
    event ConnectorRemoteMiddlewareSet(bytes32 indexed connectorId, address indexed remoteMiddleware);

    /// @notice Emitted when trusted callback caller configuration changes.
    event TrustedCallbackCallerSet(address indexed caller);

    /// @notice Emitted when the middleware updates its cached remote connector status from a response.
    event RemoteStatusUpdated(
        bytes32 indexed destinationConnectorId,
        uint256 availableBalance,
        uint256 safetyThreshold,
        uint256 minimumCharge,
        uint256 maximumCharge,
        bytes32 unitHash
    );

    /// @notice Emitted when a source connector is penalized due to an out-of-funds response.
    event ConnectorPenalized(bytes32 indexed sourceConnectorId, uint64 newPenaltyCount);

    /// @notice Emitted after the middleware successfully enqueues an outbound message.
    event OutboundMessageEnqueued(
        uint64 indexed appMsgId,
        uint64 indexed messageId,
        address indexed sourceApplication,
        address destinationApplication,
        bytes32 sourceConnectorId,
        bytes32 destinationConnectorId,
        uint256 maxChargeCommitment
    );

    /// @notice Emitted after handling an inbound message and constructing a response.
    event InboundMessageHandled(
        uint64 indexed messageId,
        address indexed destinationApplication,
        ClprTypes.ClprMiddlewareStatus status,
        bytes32 destinationConnectorId
    );

    /// @notice Emitted after delivering an inbound response to the source application.
    event InboundResponseHandled(
        uint64 indexed messageId,
        address indexed sourceApplication,
        uint64 indexed appMsgId,
        ClprTypes.ClprMiddlewareStatus status,
        bytes32 destinationConnectorId
    );

    /// @param queueAddress Queue contract used for enqueue and for message delivery callbacks.
    /// @param ledgerId_ Opaque ledger id used for connector identity configuration (spec alignment).
    constructor(address queueAddress, bytes32 ledgerId_) {
        if (queueAddress == address(0)) revert InvalidQueue();
        queue = queueAddress;
        owner = msg.sender;
        ledgerId = ledgerId_;
    }

    /// @dev Restricts messaging-layer entrypoints to the configured queue contract.
    modifier onlyQueue() {
        if (msg.sender != queue) revert QueueOnly();
        _;
    }

    /// @dev Restricts callback entrypoints to queue or trusted callback caller.
    modifier onlyQueueOrTrustedCallback() {
        if (msg.sender != queue && msg.sender != trustedCallbackCaller) revert QueueOnly();
        _;
    }

    /// @inheritdoc IClprMiddleware
    function registerLocalApplication(address application) external {
        localApplications[application] = true;
        emit ApplicationRegistered(application);
    }

    /// @inheritdoc IClprMiddleware
    function registerConnector(
        bytes32 connectorId,
        bytes32 remoteLedgerId,
        bytes32 expectedRemoteConnectorId,
        address admin
    ) external {
        if (connectorId == bytes32(0)) revert InvalidConnectorId();
        if (admin == address(0)) revert InvalidAdmin();

        // For the Solidity prototype, connectors self-register (msg.sender is the connector contract).
        // As a sanity check, ensure the connector returns the same id via its getter.
        if (IClprConnector(msg.sender).connectorId() != connectorId) revert InvalidConnectorId();

        connectors[connectorId] = ConnectorRegistration({
            connectorAddress: msg.sender,
            remoteLedgerId: remoteLedgerId,
            expectedRemoteConnectorId: expectedRemoteConnectorId,
            remoteMiddleware: address(0),
            admin: admin,
            enabled: true,
            exists: true
        });

        emit ConnectorRegistered(connectorId, msg.sender, expectedRemoteConnectorId, remoteLedgerId, admin);
    }

    /// @inheritdoc IClprMiddleware
    function disableConnector(bytes32 connectorId) external {
        ConnectorRegistration storage reg = connectors[connectorId];
        if (!reg.exists) revert InvalidConnectorId();
        if (msg.sender != reg.admin) revert AdminOnly();
        reg.enabled = false;
        emit ConnectorDisabled(connectorId);
    }

    /// @inheritdoc IClprMiddleware
    function deleteConnector(bytes32 connectorId) external {
        ConnectorRegistration storage reg = connectors[connectorId];
        if (!reg.exists) revert InvalidConnectorId();
        if (msg.sender != reg.admin) revert AdminOnly();
        delete connectors[connectorId];
        emit ConnectorDeleted(connectorId);
    }

    /// @inheritdoc IClprMiddleware
    function setConnectorRemoteMiddleware(bytes32 connectorId, address remoteMiddleware) external {
        ConnectorRegistration storage reg = connectors[connectorId];
        if (!reg.exists) revert InvalidConnectorId();
        if (msg.sender != reg.admin) revert AdminOnly();
        if (remoteMiddleware == address(0)) revert InvalidRemoteMiddleware();
        reg.remoteMiddleware = remoteMiddleware;
        emit ConnectorRemoteMiddlewareSet(connectorId, remoteMiddleware);
    }

    /// @inheritdoc IClprMiddleware
    function setTrustedCallbackCaller(address caller) external {
        if (msg.sender != owner) revert OwnerOnly();
        trustedCallbackCaller = caller;
        emit TrustedCallbackCallerSet(caller);
    }

    /// @inheritdoc IClprMiddleware
    function send(
        ClprTypes.ClprApplicationMessage calldata applicationMessage
    ) external returns (ClprTypes.ClprSendMessageStatus memory status) {
        if (!localApplications[msg.sender]) revert ApplicationNotRegistered();

        uint64 appMsgId = ++nextAppMsgId[msg.sender];
        status.appMsgId = appMsgId;
        status.status = ClprTypes.ClprSendStatus.Rejected; // default unless successfully enqueued

        if (applicationMessage.recipientId == address(0)) {
            return status;
        }

        if (applicationMessage.connectorId == bytes32(0)) {
            status.failureReason = ClprTypes.ClprSendFailureReason.ConnectorAbsent;
            status.failureSide = ClprTypes.ClprSendFailureSide.Source;
            return status;
        }

        ConnectorRegistration memory sourceReg = connectors[applicationMessage.connectorId];
        if (!sourceReg.exists || sourceReg.connectorAddress == address(0) || !sourceReg.enabled) {
            status.failureReason = ClprTypes.ClprSendFailureReason.ConnectorAbsent;
            status.failureSide = ClprTypes.ClprSendFailureSide.Source;
            return status;
        }
        if (sourceReg.remoteMiddleware == address(0)) {
            status.failureReason = ClprTypes.ClprSendFailureReason.ConnectorAbsent;
            status.failureSide = ClprTypes.ClprSendFailureSide.Source;
            return status;
        }

        bytes32 destinationConnectorId = sourceReg.expectedRemoteConnectorId;

        // Optional prefilter: if we have destination minimum charge metadata and the app's max is below it,
        // reject immediately without invoking the connector authorize hook.
        RemoteConnectorStatus memory remoteStatus = remoteStatusByDestinationConnector[destinationConnectorId];
        if (remoteStatus.known && remoteStatus.minimumCharge != 0) {
            if (applicationMessage.maxCharge.value < remoteStatus.minimumCharge) {
                return status;
            }
        }

        // If we have evidence the paired destination connector is out of funds, reject before enqueue.
        if (_isRemoteOutOfFunds(destinationConnectorId)) {
            status.failureReason = ClprTypes.ClprSendFailureReason.ConnectorOutOfFunds;
            status.failureSide = ClprTypes.ClprSendFailureSide.Destination;

            // Notify source connector of rejection (mechanism is prototype-defined).
            ClprTypes.ClprMessageDraft memory rejectedDraft = ClprTypes.ClprMessageDraft({
                senderApplicationId: msg.sender,
                applicationMessage: applicationMessage,
                destinationConnectorId: destinationConnectorId
            });
            _notifySendRejected(sourceReg.connectorAddress, rejectedDraft, status);
            return status;
        }

        // Draft passed to the source connector for authorization before enqueue.
        ClprTypes.ClprMessageDraft memory draft = ClprTypes.ClprMessageDraft({
            senderApplicationId: msg.sender,
            applicationMessage: applicationMessage,
            destinationConnectorId: destinationConnectorId
        });

        ClprTypes.ClprConnectorMessage memory connectorMessage;
        try IClprConnector(sourceReg.connectorAddress).authorize(draft) returns (
            ClprTypes.ClprConnectorMessage memory authorized
        ) {
            connectorMessage = authorized;
        } catch {
            status.failureSide = ClprTypes.ClprSendFailureSide.Source;
            return status;
        }

        if (!connectorMessage.approve) {
            status.failureSide = ClprTypes.ClprSendFailureSide.Source;
            return status;
        }

        uint256 maxChargeCommitment = _effectiveMaxCharge(applicationMessage.maxCharge.value, connectorMessage.maxCharge.value);

        ClprTypes.ClprMessage memory outboundMessage = ClprTypes.ClprMessage({
            senderApplicationId: msg.sender,
            applicationMessage: applicationMessage,
            destinationConnectorId: destinationConnectorId,
            connectorMessage: connectorMessage,
            middlewareMessage: _buildSourceMiddlewareMessage(applicationMessage.connectorId, sourceReg.remoteMiddleware)
        });

        uint64 messageId;
        try IClprQueue(queue).enqueueMessage(outboundMessage) returns (uint64 assignedMessageId) {
            messageId = assignedMessageId;
        } catch {
            return status;
        }

        pendingByMessageId[messageId] = PendingOutboundMessage({
            sourceApplication: msg.sender,
            appMsgId: appMsgId,
            sourceConnectorId: applicationMessage.connectorId,
            destinationConnectorId: destinationConnectorId,
            maxChargeCommitment: maxChargeCommitment,
            exists: true
        });

        outstandingCommitmentsByDestinationConnector[destinationConnectorId] += maxChargeCommitment;

        status.status = ClprTypes.ClprSendStatus.Accepted;

        emit OutboundMessageEnqueued(
            appMsgId,
            messageId,
            msg.sender,
            applicationMessage.recipientId,
            applicationMessage.connectorId,
            destinationConnectorId,
            maxChargeCommitment
        );
    }

    /// @inheritdoc IClprMiddleware
    function handleMessage(
        ClprTypes.ClprMessage calldata message,
        uint64 messageId
    ) external onlyQueueOrTrustedCallback returns (ClprTypes.ClprMessageResponse memory response) {
        bytes memory responseRouteData = _buildResponseRouteHeader(message.middlewareMessage.data);

        address destinationApplication = message.applicationMessage.recipientId;
        if (destinationApplication == address(0)) revert InvalidRecipient();
        if (!localApplications[destinationApplication]) revert ApplicationNotRegistered();

        // Destination connector lookup and pairing validation.
        ConnectorRegistration memory destinationReg = connectors[message.destinationConnectorId];
        if (!destinationReg.exists || destinationReg.connectorAddress == address(0) || !destinationReg.enabled) {
            ClprTypes.ClprMiddlewareStatus failureStatus = ClprTypes.ClprMiddlewareStatus.ConnectorAbsent;
            response = _buildFailureResponse(messageId, failureStatus, message.destinationConnectorId, responseRouteData);
            emit InboundMessageHandled(messageId, destinationApplication, failureStatus, message.destinationConnectorId);
            return response;
        }

        if (destinationReg.expectedRemoteConnectorId != message.applicationMessage.connectorId) {
            ClprTypes.ClprMiddlewareStatus failureStatus = ClprTypes.ClprMiddlewareStatus.ConnectorAbsent;
            response = _buildFailureResponse(messageId, failureStatus, message.destinationConnectorId, responseRouteData);
            emit InboundMessageHandled(messageId, destinationApplication, failureStatus, message.destinationConnectorId);
            return response;
        }

        ClprTypes.ClprMiddlewareStatus status;
        (response, status) = _handleMessageWithDestinationConnector(
            message,
            messageId,
            destinationApplication,
            destinationReg,
            responseRouteData
        );
        emit InboundMessageHandled(messageId, destinationApplication, status, message.destinationConnectorId);
    }

    function _handleMessageWithDestinationConnector(
        ClprTypes.ClprMessage calldata message,
        uint64 messageId,
        address destinationApplication,
        ConnectorRegistration memory destinationReg,
        bytes memory responseRouteData
    ) private returns (ClprTypes.ClprMessageResponse memory response, ClprTypes.ClprMiddlewareStatus status) {
        // Destination-side funds check: if the connector cannot reimburse, reject and do not execute the app.
        ClprTypes.ClprAmount memory minCharge = IClprConnector(destinationReg.connectorAddress).minimumCharge();
        ClprTypes.ClprAmount memory maxCharge = IClprConnector(destinationReg.connectorAddress).maximumCharge();
        ClprTypes.ClprBalanceReport memory preBalanceReport = IClprConnector(destinationReg.connectorAddress).getBalanceReport(0);

        if (_isOutOfFunds(preBalanceReport, minCharge.value)) {
            status = ClprTypes.ClprMiddlewareStatus.ConnectorOutOfFunds;
            response = _buildFailureResponse(messageId, status, message.destinationConnectorId, responseRouteData);

            // Notify destination connector of the failure outcome without reimbursing.
            ClprTypes.ClprBilling memory zeroBilling = ClprTypes.ClprBilling({
                charge: ClprTypes.ClprAmount({value: 0, unit: minCharge.unit})
            });
            response.connectorResponse =
                _notifyDestinationConnector(destinationReg.connectorAddress, message, response, zeroBilling);

            // Include policy + balance report even on failure.
            ClprTypes.ClprBalanceReport memory postReport = IClprConnector(destinationReg.connectorAddress).getBalanceReport(0);
            response.middlewareResponse = ClprTypes.ClprMiddlewareResponse({
                status: status,
                minimumCharge: minCharge,
                maximumCharge: maxCharge,
                middlewareMessage: ClprTypes.ClprMiddlewareMessage({balanceReport: postReport, data: responseRouteData})
            });
            return (response, status);
        }

        // Execute destination application.
        status = ClprTypes.ClprMiddlewareStatus.Success;
        ClprTypes.ClprApplicationResponse memory applicationResponse;
        try IClprDestinationApplication(destinationApplication).handleMessage(message.applicationMessage) returns (
            ClprTypes.ClprApplicationResponse memory appResponse
        ) {
            applicationResponse = appResponse;
        } catch (bytes memory revertData) {
            status = ClprTypes.ClprMiddlewareStatus.ApplicationFailure;
            // Prototype behavior: return raw revert bytes as the application response payload.
            applicationResponse = ClprTypes.ClprApplicationResponse({data: revertData});
        }

        response = ClprTypes.ClprMessageResponse({
            originalMessageId: messageId,
            applicationResponse: applicationResponse,
            connectorResponse: ClprTypes.ClprConnectorResponse({data: bytes("")}),
            middlewareResponse: ClprTypes.ClprMiddlewareResponse({
                status: status,
                minimumCharge: minCharge,
                maximumCharge: maxCharge,
                middlewareMessage: ClprTypes.ClprMiddlewareMessage({balanceReport: preBalanceReport, data: responseRouteData})
            })
        });

        // Notify destination connector and reimburse synchronously (best-effort; failures are non-fatal).
        {
            ClprTypes.ClprBilling memory billing = ClprTypes.ClprBilling({charge: minCharge});
            response.connectorResponse =
                _notifyDestinationConnector(destinationReg.connectorAddress, message, response, billing);
        }

        // Update balance report after reimbursement and include it in the middleware response.
        ClprTypes.ClprBalanceReport memory postBalanceReport = IClprConnector(destinationReg.connectorAddress).getBalanceReport(0);
        response.middlewareResponse.middlewareMessage = ClprTypes.ClprMiddlewareMessage({
            balanceReport: postBalanceReport,
            data: responseRouteData
        });

        return (response, status);
    }

    /// @inheritdoc IClprMiddleware
    function handleMessageResponse(ClprTypes.ClprMessageResponse calldata response) external onlyQueueOrTrustedCallback {
        PendingOutboundMessage memory pending = pendingByMessageId[response.originalMessageId];
        if (!pending.exists) revert UnknownMessage();
        if (!localApplications[pending.sourceApplication]) revert ApplicationNotRegistered();

        delete pendingByMessageId[response.originalMessageId];

        // Reduce outstanding commitments for this destination connector now that the response was observed.
        uint256 currentCommitments = outstandingCommitmentsByDestinationConnector[pending.destinationConnectorId];
        if (currentCommitments >= pending.maxChargeCommitment) {
            outstandingCommitmentsByDestinationConnector[pending.destinationConnectorId] =
                currentCommitments - pending.maxChargeCommitment;
        } else {
            outstandingCommitmentsByDestinationConnector[pending.destinationConnectorId] = 0;
        }

        _updateRemoteStatusFromResponse(response.middlewareResponse);

        // Penalize the source connector on connector_out_of_funds responses (placeholder policy).
        if (response.middlewareResponse.status == ClprTypes.ClprMiddlewareStatus.ConnectorOutOfFunds) {
            uint64 newPenalty = ++penaltyCountBySourceConnector[pending.sourceConnectorId];
            emit ConnectorPenalized(pending.sourceConnectorId, newPenalty);
        }

        // Translate middleware failures into an application response payload (prototype-defined).
        ClprTypes.ClprApplicationResponse memory deliveryResponse = response.applicationResponse;
        if (
            response.middlewareResponse.status == ClprTypes.ClprMiddlewareStatus.ConnectorAbsent
                || response.middlewareResponse.status == ClprTypes.ClprMiddlewareStatus.ConnectorOutOfFunds
        ) {
            deliveryResponse = _buildFailureApplicationResponse(response.middlewareResponse.status);
        }

        IClprSourceApplication(pending.sourceApplication).handleResponse(deliveryResponse, pending.appMsgId);

        // Notify the source connector of connector and application response payloads (best-effort).
        ConnectorRegistration memory sourceReg = connectors[pending.sourceConnectorId];
        if (sourceReg.exists && sourceReg.connectorAddress != address(0)) {
            _notifySourceConnector(sourceReg.connectorAddress, response, deliveryResponse, pending.appMsgId);
        }

        emit InboundResponseHandled(
            response.originalMessageId,
            pending.sourceApplication,
            pending.appMsgId,
            response.middlewareResponse.status,
            pending.destinationConnectorId
        );
    }

    function _buildSourceMiddlewareMessage(
        bytes32 sourceConnectorId,
        address destinationMiddleware
    ) private view returns (ClprTypes.ClprMiddlewareMessage memory middlewareMessage) {
        ConnectorRegistration memory reg = connectors[sourceConnectorId];
        if (!reg.exists || reg.connectorAddress == address(0)) {
            // Source connector is missing; return empty metadata and let the destination handle it as needed.
            middlewareMessage = ClprTypes.ClprMiddlewareMessage({
                balanceReport: ClprTypes.ClprBalanceReport({
                    connectorId: sourceConnectorId,
                    availableBalance: ClprTypes.ClprAmount({value: 0, unit: ""}),
                    safetyThreshold: ClprTypes.ClprAmount({value: 0, unit: ""}),
                    outstandingCommitments: ClprTypes.ClprAmount({value: 0, unit: ""})
                }),
                data: bytes("")
            });
            return middlewareMessage;
        }

        // For this prototype, source-connector outstanding commitments are not modeled; provide 0.
        ClprTypes.ClprBalanceReport memory report = IClprConnector(reg.connectorAddress).getBalanceReport(0);
        middlewareMessage = ClprTypes.ClprMiddlewareMessage({
            balanceReport: report,
            data: abi.encode(ROUTE_VERSION, reg.remoteLedgerId, address(this), destinationMiddleware)
        });
    }

    function _effectiveMaxCharge(uint256 appMax, uint256 connectorMax) private pure returns (uint256) {
        return appMax < connectorMax ? appMax : connectorMax;
    }

    function _isRemoteOutOfFunds(bytes32 destinationConnectorId) private view returns (bool) {
        RemoteConnectorStatus memory remote = remoteStatusByDestinationConnector[destinationConnectorId];
        if (!remote.known || remote.unavailable) return false;
        if (remote.availableBalance <= remote.safetyThreshold) return true;

        uint256 threshold = remote.availableBalance - remote.safetyThreshold;
        return outstandingCommitmentsByDestinationConnector[destinationConnectorId] >= threshold;
    }

    function _isOutOfFunds(
        ClprTypes.ClprBalanceReport memory report,
        uint256 requiredCharge
    ) private pure returns (bool) {
        if (report.availableBalance.value <= report.safetyThreshold.value) return true;
        uint256 capacity = report.availableBalance.value - report.safetyThreshold.value;
        return capacity < requiredCharge;
    }

    function _notifySendRejected(
        address connectorAddress,
        ClprTypes.ClprMessageDraft memory draft,
        ClprTypes.ClprSendMessageStatus memory status
    ) private {
        // Best-effort; notification failures must not block the application call.
        try IClprConnector(connectorAddress).notifySendRejected(
            draft,
            status.appMsgId,
            status.failureReason,
            status.failureSide
        ) {} catch {}
    }

    function _notifyDestinationConnector(
        address destinationConnector,
        ClprTypes.ClprMessage calldata message,
        ClprTypes.ClprMessageResponse memory response,
        ClprTypes.ClprBilling memory billing
    ) private returns (ClprTypes.ClprConnectorResponse memory connectorResponse) {
        try IClprConnector(destinationConnector).handleMessage(message, response, billing) returns (
            ClprTypes.ClprConnectorResponse memory produced
        ) {
            connectorResponse = produced;
        } catch {
            connectorResponse = ClprTypes.ClprConnectorResponse({data: bytes("")});
        }
    }

    function _notifySourceConnector(
        address sourceConnector,
        ClprTypes.ClprMessageResponse calldata response,
        ClprTypes.ClprApplicationResponse memory deliveryResponse,
        uint64 appMsgId
    ) private {
        try IClprConnector(sourceConnector).handleConnectorResponse(response.connectorResponse) {} catch {}
        try IClprConnector(sourceConnector).handleApplicationResponse(deliveryResponse, appMsgId) {} catch {}
    }

    function _updateRemoteStatusFromResponse(ClprTypes.ClprMiddlewareResponse calldata middlewareResponse) private {
        ClprTypes.ClprBalanceReport calldata report = middlewareResponse.middlewareMessage.balanceReport;

        RemoteConnectorStatus storage remote = remoteStatusByDestinationConnector[report.connectorId];
        remote.availableBalance = report.availableBalance.value;
        remote.safetyThreshold = report.safetyThreshold.value;
        remote.minimumCharge = middlewareResponse.minimumCharge.value;
        remote.maximumCharge = middlewareResponse.maximumCharge.value;
        remote.unit = report.availableBalance.unit;
        remote.known = true;

        emit RemoteStatusUpdated(
            report.connectorId,
            remote.availableBalance,
            remote.safetyThreshold,
            remote.minimumCharge,
            remote.maximumCharge,
            keccak256(bytes(remote.unit))
        );

        if (middlewareResponse.status == ClprTypes.ClprMiddlewareStatus.ConnectorAbsent) {
            remote.unavailable = true;
        }
    }

    function _buildFailureResponse(
        uint64 messageId,
        ClprTypes.ClprMiddlewareStatus status,
        bytes32 destinationConnectorId,
        bytes memory responseRouteData
    ) private pure returns (ClprTypes.ClprMessageResponse memory response) {
        response = ClprTypes.ClprMessageResponse({
            originalMessageId: messageId,
            applicationResponse: ClprTypes.ClprApplicationResponse({data: bytes("")}),
            connectorResponse: ClprTypes.ClprConnectorResponse({data: bytes("")}),
            middlewareResponse: ClprTypes.ClprMiddlewareResponse({
                status: status,
                minimumCharge: ClprTypes.ClprAmount({value: 0, unit: ""}),
                maximumCharge: ClprTypes.ClprAmount({value: type(uint256).max, unit: ""}),
                middlewareMessage: ClprTypes.ClprMiddlewareMessage({
                    balanceReport: ClprTypes.ClprBalanceReport({
                        connectorId: destinationConnectorId,
                        availableBalance: ClprTypes.ClprAmount({value: 0, unit: ""}),
                        safetyThreshold: ClprTypes.ClprAmount({value: 0, unit: ""}),
                        outstandingCommitments: ClprTypes.ClprAmount({value: 0, unit: ""})
                    }),
                    data: responseRouteData
                })
            })
        });
    }

    function _buildResponseRouteHeader(bytes memory requestRouteData) private view returns (bytes memory) {
        (uint8 version, bytes32 remoteLedgerId, address sourceMiddleware, address destinationMiddleware) =
            abi.decode(requestRouteData, (uint8, bytes32, address, address));
        if (version != ROUTE_VERSION) revert InvalidRouteHeader();
        if (remoteLedgerId == bytes32(0)) revert InvalidRouteHeader();
        if (sourceMiddleware == address(0)) revert InvalidRouteHeader();
        if (destinationMiddleware != address(this)) revert InvalidRecipient();
        return abi.encode(version, remoteLedgerId, sourceMiddleware);
    }

    function _buildFailureApplicationResponse(
        ClprTypes.ClprMiddlewareStatus status
    ) private pure returns (ClprTypes.ClprApplicationResponse memory response) {
        // Prototype-defined failure envelope:
        // 0xCLPR | status_byte
        response = ClprTypes.ClprApplicationResponse({data: abi.encodePacked(bytes4("CLPR"), uint8(status))});
    }
}
