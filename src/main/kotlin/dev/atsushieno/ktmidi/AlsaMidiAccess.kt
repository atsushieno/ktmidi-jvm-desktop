package dev.atsushieno.ktmidi

import dev.atsushieno.alsakt.*

internal fun Byte.toUnsigned() : Int = if (this < 0) this + 0x100 else this.toInt()

class AlsaMidiAccess : MidiAccess() {

    companion object {
        private const val midi_port_type = AlsaPortType.MidiGeneric or AlsaPortType.Application

        private val system_watcher: AlsaSequencer = AlsaSequencer(AlsaIOType.Duplex, AlsaIOMode.NonBlocking)

        private const val input_requirements = AlsaPortCapabilities.Read or AlsaPortCapabilities.SubsRead
        private const val output_requirements = AlsaPortCapabilities.Write or AlsaPortCapabilities.SubsWrite
        private const val output_connected_cap = AlsaPortCapabilities.Read or AlsaPortCapabilities.NoExport
        private const val input_connected_cap = AlsaPortCapabilities.Write or AlsaPortCapabilities.NoExport
        private const val virtual_output_connected_cap = AlsaPortCapabilities.Write or AlsaPortCapabilities.SubsWrite
        private const val virtual_input_connected_cap = AlsaPortCapabilities.Read or AlsaPortCapabilities.SubsRead
    }

    private fun enumerateMatchingPorts ( seq: AlsaSequencer, cap:  Int) : Iterable<AlsaPortInfo> {
        val clientInfo = AlsaClientInfo().apply { client = -1 }
        return sequence {
            while (seq.queryNextClient(clientInfo)) {
                val portInfo = AlsaPortInfo().apply {
                    client = clientInfo.client
                    port = -1
                }
                while (seq.queryNextPort(portInfo))
                    if ((portInfo.portType and midi_port_type) != 0 &&
                (portInfo.capabilities and cap) == cap)
                yield( portInfo.clone())
            }
        }.asIterable()
    }

    private fun enumerateAvailableInputPorts (): Iterable<AlsaPortInfo> {
        return enumerateMatchingPorts (system_watcher, input_requirements)
    }

    private fun enumerateAvailableOutputPorts () : Iterable<AlsaPortInfo> {
        return enumerateMatchingPorts (system_watcher, output_requirements)
    }

    // [input device port] --> [RETURNED PORT] --> app handles messages
    private fun createInputConnectedPort (seq: AlsaSequencer , pinfo:  AlsaPortInfo, portName: String = "ktmidi ALSA input") : AlsaPortInfo {
        val portId = seq.createSimplePort (portName, input_connected_cap, midi_port_type)
        val sub =  AlsaPortSubscription ()
        sub.destination.client = seq.currentClientId.toByte()
        sub.destination.port = portId.toByte()
        sub.sender.client = pinfo.client.toByte()
        sub.sender.port = pinfo.port.toByte()
        seq.subscribePort (sub)
        return seq.getPort (sub.destination.client.toUnsigned(), sub.destination.port.toUnsigned())
    }

    // app generates messages --> [RETURNED PORT] --> [output device port]
    private fun createOutputConnectedPort ( seq: AlsaSequencer, pinfo: AlsaPortInfo, portName: String = "ktmidi ALSA output") : AlsaPortInfo {
        val portId = seq.createSimplePort (portName, output_connected_cap, midi_port_type)
        val sub = AlsaPortSubscription ()
        sub.sender.client = seq.currentClientId.toByte()
        sub.sender.port = portId.toByte()
        sub.destination.client = pinfo.client.toByte()
        sub.destination.port = pinfo.port.toByte()
        seq.subscribePort (sub)
        return seq.getPort (sub.sender.client.toUnsigned(), sub.sender.port.toUnsigned())
    }

    override val inputs : Iterable<MidiPortDetails>
        get() = enumerateAvailableInputPorts ().map { p -> AlsaMidiPortDetails (p) }

    override val outputs : Iterable<MidiPortDetails>
        get() = enumerateAvailableOutputPorts ().map { p -> AlsaMidiPortDetails (p) }

    // FIXME: make it suspend fun at some stage (otherwise it is not Async at all...
    override fun openInputAsync (portId: String): MidiInput {
        val sourcePort = inputs.firstOrNull { p -> p.id == portId } as AlsaMidiPortDetails?
            ?: throw IllegalArgumentException ("Port '$portId' does not exist.")
        val seq = AlsaSequencer (AlsaIOType.Input, AlsaIOMode.NonBlocking)
        val appPort = createInputConnectedPort (seq, sourcePort.portInfo)
        return AlsaMidiInput (seq, AlsaMidiPortDetails (appPort), sourcePort)
    }

    // FIXME: make it suspend fun at some stage (otherwise it is not Async at all...
    override fun openOutputAsync ( portId:String) : MidiOutput {
        val destPort = outputs.firstOrNull { p -> p.id == portId } as AlsaMidiPortDetails?
            ?: throw IllegalArgumentException ("Port '$portId' does not exist.")
        val seq = AlsaSequencer (AlsaIOType.Output, AlsaIOMode.None)
        val appPort = createOutputConnectedPort (seq, destPort.portInfo)
        return AlsaMidiOutput (seq, AlsaMidiPortDetails (appPort), destPort)
    }

    override suspend fun createVirtualInputSender ( context: PortCreatorContext) : MidiOutput {
        val seq = AlsaSequencer (AlsaIOType.Duplex, AlsaIOMode.NonBlocking)
        val portNumber = seq.createSimplePort (context.portName,
            virtual_input_connected_cap,
            midi_port_type)
        seq.setClientName (context.applicationName)
        val port = seq.getPort (seq.currentClientId, portNumber)
        val details =  AlsaMidiPortDetails (port)
        val send : (ByteArray, Int, Int, Long) -> Unit = { buffer, start, length, timestamp ->
            seq.send(portNumber, buffer, start, length)
        }
        return SimpleVirtualMidiOutput (details) { seq.deleteSimplePort(portNumber) }.apply { onSend = send }
    }

    override suspend fun createVirtualOutputReceiver ( context:PortCreatorContext): MidiInput {
        val seq = AlsaSequencer (AlsaIOType.Duplex, AlsaIOMode.NonBlocking)
        val portNumber = seq.createSimplePort (context.portName,
            virtual_output_connected_cap,
            midi_port_type)
        seq.setClientName (context.applicationName)
        val port = seq.getPort (seq.currentClientId, portNumber)
        val details = AlsaMidiPortDetails (port)
        return SimpleVirtualMidiInput (details) { seq.deleteSimplePort(portNumber) }
    }

}

class AlsaMidiPortDetails(private val port: AlsaPortInfo) : MidiPortDetails {

    internal val portInfo: AlsaPortInfo
        get() = port

    override val id: String
        get() = port.id

    override val manufacturer: String
        get() = port.manufacturer

    override val name: String
        get() = port.name

    override val version: String
        get() = port.version
}

class AlsaMidiInput(private val seq: AlsaSequencer, private val appPort: AlsaMidiPortDetails, private val sourcePort: AlsaMidiPortDetails) : MidiInput {

    override val details: MidiPortDetails
        get() = sourcePort

    override var connectionState: MidiPortConnectionState = MidiPortConnectionState.OPEN

    override var midiProtocol: Int
        get() = MidiCIProtocolValue.MIDI1
        set(value) {
            if (value != MidiCIProtocolValue.MIDI1)
                throw UnsupportedOperationException("ALSA does not support MIDI 2.0 yet")
        }

    private var messageReceived: OnMidiReceivedEventListener? = null

    override fun setMessageReceivedListener(messageReceived: OnMidiReceivedEventListener) {
        this.messageReceived = messageReceived
    }

    override fun close () {
        // unsubscribe the app port from the MIDI input, and then delete the port.
        val q = AlsaSubscriptionQuery().apply {
            type = AlsaSubscriptionQueryType.Write
            index = 0
        }
        q.address.client = appPort.portInfo.client.toByte()
        q.address.port = appPort.portInfo.port.toByte()
        if (seq.queryPortSubscribers (q))
            seq.disconnectDestination (appPort.portInfo.port, q.address.client.toUnsigned(), q.address.port.toUnsigned())
        seq.deleteSimplePort (appPort.portInfo.port)
    }

    init {
        val buffer = ByteArray(0x200)
        val received : (ByteArray, Int, Int) -> Unit = { buf, start, len ->
            messageReceived?.onEventReceived (buf, start, len, 0)
        }
        seq.startListening (appPort.portInfo.port, buffer, onReceived = received, timeout = -1)
    }
}

class AlsaMidiOutput(private val seq: AlsaSequencer, private val appPort: AlsaMidiPortDetails, private val targetPort: AlsaMidiPortDetails) : MidiOutput {

    override val details: MidiPortDetails
        get() = targetPort

    override var connectionState: MidiPortConnectionState = MidiPortConnectionState.OPEN

    override var midiProtocol: Int
        get() = MidiCIProtocolValue.MIDI1
        set(value) {
            if (value != MidiCIProtocolValue.MIDI1)
                throw UnsupportedOperationException("ALSA does not support MIDI 2.0 yet")
        }

    override fun close() {
        // unsubscribe the app port from the MIDI output, and then delete the port.
        val q = AlsaSubscriptionQuery().apply {
            type = AlsaSubscriptionQueryType.Read
            index = 0
        }
        q.address.client = appPort.portInfo.client.toByte()
        q.address.port = appPort.portInfo.port.toByte()
        if (seq.queryPortSubscribers(q))
            seq.disconnectDestination(appPort.portInfo.port, q.address.client.toUnsigned(), q.address.port.toUnsigned())
        seq.deleteSimplePort(appPort.portInfo.port)
    }

    override fun send(mevent: ByteArray, offset: Int, length: Int, timestamp: Long) {
        seq.send(appPort.portInfo.port, mevent, offset, length)
    }
}