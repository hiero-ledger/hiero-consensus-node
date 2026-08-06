# Overview

The negotiator is part of the communication layer. It gets activated when a connection is established to a peer, and
starts protocol negotiation. If there is no protocol to run, it will just maintain the connection with keepalive
messages.

Both nodes can initiate a protocol over the same connection. This can occur in parallel so an extra step might be needed
to determine which protocol to run.

## Communication

Each peer only sends one byte at a time, then waits for a byte from the peer. Depending on what the peer sends, there
might be an additional step.

Example communication:

[![](https://mermaid.ink/img/pako:eNqlkkFPwzAMhf9KlHMn7j0MtYwTEge45uIlb2uk1gmJO2ma9t_JtK2IMhAIn5z4s9-T5YO2wUHXOuNtBFusPG0TDYZViUhJvPWRWFSjKKum9xZfa-2p1oa14al2Tko0i-WyrdVDRyJI9-d_YjcBbQGaOYAr8BwEKuyQVFN9jFFZinz-j94TEKn3O8wEP9tRe-Q7Dje9rBBLV1aBJ2oSuWVppvgHTzPh8uw8b1VHsTj4aQsvsIEZVr7fwxz5paiu9IA0kHfleA6nDqOlwwCj65I6bGjsxWjDx4KO0ZHg0XkJSdcb6jMqTaOE1z1bXUsacYUuB3ihju_T3cx2)](https://mermaid-js.github.io/mermaid-live-editor/edit/#pako:eNqlkkFPwzAMhf9KlHMn7j0MtYwTEge45uIlb2uk1gmJO2ma9t_JtK2IMhAIn5z4s9-T5YO2wUHXOuNtBFusPG0TDYZViUhJvPWRWFSjKKum9xZfa-2p1oa14al2Tko0i-WyrdVDRyJI9-d_YjcBbQGaOYAr8BwEKuyQVFN9jFFZinz-j94TEKn3O8wEP9tRe-Q7Dje9rBBLV1aBJ2oSuWVppvgHTzPh8uw8b1VHsTj4aQsvsIEZVr7fwxz5paiu9IA0kHfleA6nDqOlwwCj65I6bGjsxWjDx4KO0ZHg0XkJSdcb6jMqTaOE1z1bXUsacYUuB3ihju_T3cx2)

## State diagram

While the communication is quite simple, the state diagram for a negotiator is a little more complicated.

[![](https://mermaid.ink/img/pako:eNptU1FrwyAQ_ivi42gZ7DGshcL2EFq6kjzsYdmD02trZzQY01FK__tOo12zRiHEu----74Tz5QbATSjrWMOXiTbWVZPj0-VJrjykmQk19JJpkhA9PFyifEStCPfAA1T8pgSeUrIUJUKFgXG35l0ZGssWXD-WMChTxW-pAAOSCL-lZUrT6ewR3_erPG8scYZbhTRsDMBLCrtt0d8PHyS6XSOyjMv2LqrER8tlxgdkR09BcgqIzapGYFFWR6KvZBOQePuWIo8sAQ7w1q06wGbdVTCOB8QpIVWrz2a5FgYDaN0XnWgs3AA_s90HnPD2cbr8ohF0au92n3-svO3wn-TCaLMD1hUIo2V7jQUgbebPHn8mKUIidMdyLwREmzIulOOaTBdO5tpcwcKoxuATtDeoW5vgOzlbj9Q_7fphNZgayYFPoKzp6mo20MNFc3wV8CWYaeKVvqC0K4ROMBXIZ2xNNsy1cKEss6Z8qQ5zZztIIHiW4qoyy-ZcADx)](https://mermaid-js.github.io/mermaid-live-editor/edit/#pako:eNptU1FrwyAQ_ivi42gZ7DGshcL2EFq6kjzsYdmD02trZzQY01FK__tOo12zRiHEu----74Tz5QbATSjrWMOXiTbWVZPj0-VJrjykmQk19JJpkhA9PFyifEStCPfAA1T8pgSeUrIUJUKFgXG35l0ZGssWXD-WMChTxW-pAAOSCL-lZUrT6ewR3_erPG8scYZbhTRsDMBLCrtt0d8PHyS6XSOyjMv2LqrER8tlxgdkR09BcgqIzapGYFFWR6KvZBOQePuWIo8sAQ7w1q06wGbdVTCOB8QpIVWrz2a5FgYDaN0XnWgs3AA_s90HnPD2cbr8ohF0au92n3-svO3wn-TCaLMD1hUIo2V7jQUgbebPHn8mKUIidMdyLwREmzIulOOaTBdO5tpcwcKoxuATtDeoW5vgOzlbj9Q_7fphNZgayYFPoKzp6mo20MNFc3wV8CWYaeKVvqC0K4ROMBXIZ2xNNsy1cKEss6Z8qQ5zZztIIHiW4qoyy-ZcADx)

Run-through of the state diagram:

- **Initial state** - each peer starts from this state and is expected to send a byte. This can be:
    - Keepalive - if we do not wish to initiate a protocol
    - Protocol ID - if we wish to initiate a protocol, we send its ID
- **Sent keepalive** - if we sent a keepalive byte, the peer sent a byte in parallel, so we wait for that byte which can
  be one of the following:
    - Keepalive - no one initiated a protocol, we sleep and try again
    - Protocol ID - they initiated a protocol, we should respond with accept or reject
- **Received initiate** - we are expected to respond with either accept or reject, so we ask the protocol and respond
  appropriately. If we accept, the protocol starts, if not, the negotiation failed.
- **Sent initiate** - we initiated a protocol, the peer sent a byte in parallel, so we wait for that byte which could
  be:
    - Keepalive - in this case they have to respond to our initiation
    - Protocol ID - in case we both initiated a protocol in the same time, we proceed depending on which protocol they
      initiated:
        - Same ID - in case we both initiated the same protocol in parallel, then the initiated protocol must specify
          what happens on a simultaneous initiate. For example, if we both want to start Chatter, we should start. If
          both want to reconnect, that means that both have fallen behind and there is no point in proceeding. Also, it
          would not be known who the teacher and who the learner is. So on a simultaneous initiate, we either start the
          protocol, or the negotiation failed, depending on the protocol.
        - Different ID - in case we initiated different protocolFactories in parallel, then the priority of the protocol decides
          the next step.
            - If their protocol has a higher priority, we respond with accept or reject
            - If our protocol has a higher priority, we wait for them to respond with accept or reject
- **Wait for Acc/Rej** - we wait for the peer to respond with either accept or reject
- **Received initiate** - we should respond with either accept or reject
- **Sleep** - we do nothing for a while, this is to avoid sending lots of data over the network if no protocol can be
  negotiated at this time
- **Protocol negotiated** - we run the protocol which takes over the connection until its is done, if ever

**NOTE:** this could be simplified somewhat if keepalive was not a special message, but a default protocol with the
lowest priority. this optimization could be done in the future. 