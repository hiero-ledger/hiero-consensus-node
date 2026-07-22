# ReconnectBench GitHub Issues

## Fail reconnect verification when the resulting map is incorrect

`benchmark.verifyResult` is enabled by default, but `VirtualMapBaseBench.verifyMap(...)` only writes an error to the log
when values are wrong or missing. JMH can therefore report a successful benchmark result even though reconnect produced
an incorrect map.

Make verification failure fail the JMH invocation. This can be done by returning verification counts to ReconnectBench
or by adding a reconnect-specific verifier that throws when bad or missing values are found. Ensure the reconnected map
is still released when verification throws, and cover both successful and failing verification paths with tests.

## Track loopback TCP transport validation on the socket branch

Loopback TCP validation is already in progress on the separate socket-transport branch, but no GitHub issue currently
tracks that work. The socket transport is used to compare the in-memory `SimulatedNetworkChannel` with a real local TCP
connection, including operating-system socket buffers, scheduling, thread parking, and pacing behavior that the
simulator does not model directly.

Create an issue for the existing branch work and document the intended validation scope. Compare loopback TCP and
`SimulatedNetworkChannel` using the same saved state and network targets, record the relevant socket and JFR
diagnostics, and verify that both transports support the same realistic operating-point trend. Keep loopback TCP
isolated from the simulator-only branch and treat it as a diagnostic validation tool rather than the default
ReconnectBench transport.
