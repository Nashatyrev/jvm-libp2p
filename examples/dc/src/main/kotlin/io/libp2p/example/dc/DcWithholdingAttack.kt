package io.libp2p.example.dc

import com.google.protobuf.ByteString
import io.libp2p.core.crypto.sha256
import io.libp2p.pubsub.DEFAULT_PUBSUB_MESSAGE_ID_LENGTH
import io.libp2p.quicsim.sim.SimNodeId
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import pubsub.pb.Rpc
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A withholding attack: [attackerNodeIds] behave like any other node until [startsAt], then stop
 * passing on message payloads in every form -- no mesh forwarding, no answer to an IWANT -- while
 * still announcing what they hold.
 *
 * The attackers keep their meshes, their subscriptions and their router's own heartbeat IHAVE
 * gossip; only the `publish` entries of what they send are removed. That is exactly the shape that
 * hurts a node pulling from one announcer at a time ([io.libp2p.pubsub.gossip.IWantPolicy.DISCIPLINED]):
 * each announcer it picks may be one that will never deliver. A node that asks every announcer
 * ([io.libp2p.pubsub.gossip.IWantPolicy.ALL_ANNOUNCERS]) only needs one honest one among them.
 *
 * With [eagerIHave] an attacker also announces every message it receives to *all* its peers --
 * mesh peers included -- within [iHaveBatchWindow], instead of leaving it to its heartbeat's random
 * gossip selection. That puts its offer at the head of every victim's offer table, ahead of the
 * honest announcers, who only gossip at their next heartbeat. Batching keeps each attacker under
 * the victim's per-heartbeat IHAVE message limit (`maxIHaveMessages`), past which it would simply
 * be ignored.
 */
data class DcWithholdingAttack(
    val attackerNodeIds: Set<SimNodeId>,
    val startsAt: Duration,
    val eagerIHave: Boolean = true,
    val iHaveBatchWindow: Duration = 100.milliseconds
) {
    init {
        require(!startsAt.isNegative()) { "startsAt must be >= 0, got $startsAt" }
        require(iHaveBatchWindow.isPositive()) { "iHaveBatchWindow must be > 0, got $iHaveBatchWindow" }
    }

    fun isAttacker(id: SimNodeId): Boolean = id in attackerNodeIds

    companion object {
        /**
         * Draws `round(fraction * nodeCount)` attackers uniformly from the network, never from
         * [exclude] -- the publishers of the messages being measured, since a withholding publisher
         * makes the question trivial.
         */
        fun <R> pickAttackers(
            network: DcNetwork<R>,
            fraction: Double,
            exclude: Set<SimNodeId>,
            randomSeed: Long
        ): Set<SimNodeId> {
            require(fraction in 0.0..1.0) { "fraction must be in [0, 1], got $fraction" }
            val count = Math.round(fraction * network.nodeCount).toInt()
            val candidates = network.nodes.map { it.simNodeId }.filterNot { it in exclude }
            require(count <= candidates.size) {
                "$count attackers asked for, but only ${candidates.size} nodes are not excluded"
            }
            return candidates.shuffled(Random(randomSeed)).take(count).toSet()
        }
    }
}

/**
 * The attacker's side of [DcWithholdingAttack], as a handler on every gossip stream of one attacking
 * node -- placed where [GossipByteCounter] would be, after the protobuf codec, so it sees [Rpc.RPC]s.
 *
 * It strips `publish` from every outbound RPC once the attack has started (an RPC left empty is not
 * sent at all), and, with [DcWithholdingAttack.eagerIHave], answers each newly received message
 * with an IHAVE to every peer. [counter] is put *before* it in the pipeline, between it and the
 * codec, so the traffic counters see what actually reaches the wire.
 *
 * [currentTimeSupplier] and [scheduler] belong to the simulated node and are only available once it
 * has started -- see [DcNodeProgram.onAllConnected]. Until both are set the node is honest.
 */
@ChannelHandler.Sharable
class DcWithholdingHandler(
    private val attack: DcWithholdingAttack,
    private val counter: ChannelHandler
) : ChannelDuplexHandler() {

    @Volatile
    var currentTimeSupplier: (() -> Duration)? = null

    @Volatile
    var scheduler: ((Duration, () -> Unit) -> Unit)? = null

    private val _withheldPublishes = AtomicLong()
    private val _eagerIHaveIds = AtomicLong()

    /** Publish entries removed from outbound RPCs: forwards and IWANT replies that never happened. */
    val withheldPublishes: Long get() = _withheldPublishes.get()

    /** Message ids announced eagerly, counted once per peer they went to. */
    val eagerIHaveIds: Long get() = _eagerIHaveIds.get()

    private val lock = Any()

    /**
     * Streams this node writes on -- one per peer, since gossip is semi-duplex: a peer is written to
     * on the stream we opened and read from on the one it opened. A stream is recognised as ours
     * by the router writing to it, which it does first thing (its subscriptions) on activation.
     */
    private val outboundContexts = linkedSetOf<ChannelHandlerContext>()
    private val announced = hashSetOf<ByteString>()
    private val pendingByTopic = linkedMapOf<String, MutableList<ByteString>>()
    private var flushScheduled = false

    private fun attacking(): Boolean {
        val now = currentTimeSupplier ?: return false
        return now() >= attack.startsAt
    }

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        ctx.pipeline().addBefore(ctx.name(), null, counter)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        synchronized(lock) { outboundContexts -= ctx }
        super.channelInactive(ctx)
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        synchronized(lock) { outboundContexts -= ctx }
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (msg !is Rpc.RPC) {
            ctx.write(msg, promise)
            return
        }
        synchronized(lock) { outboundContexts += ctx }
        if (msg.publishCount == 0 || !attacking()) {
            ctx.write(msg, promise)
            return
        }
        _withheldPublishes.addAndGet(msg.publishCount.toLong())
        val stripped = msg.toBuilder().clearPublish().build()
        if (stripped.serializedSize == 0) {
            promise.setSuccess()
        } else {
            ctx.write(stripped, promise)
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (attack.eagerIHave && msg is Rpc.RPC && msg.publishCount > 0 && attacking()) {
            queueAnnouncements(msg.publishList)
        }
        ctx.fireChannelRead(msg)
    }

    private fun queueAnnouncements(messages: List<Rpc.Message>) {
        val schedule = scheduler ?: return
        val armFlush = synchronized(lock) {
            messages.forEach { message ->
                val topic = message.topicIDsList.firstOrNull() ?: return@forEach
                val id = ByteString.copyFrom(
                    sha256(message.data.toByteArray()).copyOf(DEFAULT_PUBSUB_MESSAGE_ID_LENGTH)
                )
                if (announced.add(id)) pendingByTopic.getOrPut(topic) { mutableListOf() } += id
            }
            (pendingByTopic.isNotEmpty() && !flushScheduled).also { if (it) flushScheduled = true }
        }
        if (armFlush) schedule(attack.iHaveBatchWindow) { flushAnnouncements() }
    }

    private fun flushAnnouncements() {
        val (targets, pending) = synchronized(lock) {
            flushScheduled = false
            val pending = pendingByTopic.toMap()
            pendingByTopic.clear()
            outboundContexts.toList() to pending
        }
        if (pending.isEmpty()) return
        val control = Rpc.ControlMessage.newBuilder()
        pending.forEach { (topic, ids) ->
            control.addIhave(Rpc.ControlIHave.newBuilder().setTopicID(topic).addAllMessageIDs(ids))
        }
        val rpc = Rpc.RPC.newBuilder().setControl(control).build()
        val idCount = pending.values.sumOf { it.size }.toLong()
        targets.forEach { ctx ->
            ctx.writeAndFlush(rpc)
            _eagerIHaveIds.addAndGet(idCount)
        }
    }
}
