package io.libp2p.example.dc

import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion
import io.libp2p.quicsim.udpnetwork.Bandwidth
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Everything a scenario run needs, with defaults inlined here.
 *
 * A YAML file overrides only the keys it mentions (see [DcRunConfigYaml]), so a scenario file states
 * what is interesting about that run and nothing else, and the defaults stay visible in code rather
 * than spread across invocation flags.
 */
data class DcRunConfig(
    val name: String = "unnamed",
    val population: DcPopulationConfig = DcPopulationConfig(),
    val attestation: DcAttestationRunConfig = DcAttestationRunConfig(),
    val gossip: DcGossipConfig = DcGossipConfig(),
    val run: DcRunSettings = DcRunSettings()
) {
    fun describe(): String = buildString {
        appendLine("scenario: $name")
        appendLine(
            "  population: ${population.nodes} nodes x ${population.validatorsPerNode} validators " +
                "(${population.validatorCount} total), ${population.bandwidth}, " +
                "peers=${population.peers}, ${population.subnetsPerNode} of ${population.subnetCount} subnets"
        )
        appendLine(
            "  attestation: mode=${attestation.mode} waves=${attestation.waveCount} " +
                "size=${attestation.sizeBytes}B warmup=${attestation.warmup} settle=${attestation.settle}"
        )
        appendLine("  gossip: ${gossip.describe()}")
        appendLine("  run: seed=${run.seed} minPeersPerSubnet=${run.minPeersPerSubnet}")
    }
}

/** Who is on the network. A single homogeneous group is enough for most runs; extend as needed. */
data class DcPopulationConfig(
    val nodes: Int = 100,
    val validatorsPerNode: Int = 1,
    val bandwidth: DcBandwidthPreset = DcBandwidthPreset.RESIDENTIAL,
    val peers: Int = 20,
    val subnetCount: Int = 64,
    val subnetsPerNode: Int = 2,
    val seed: Long = 1
) {
    val validatorCount: Int get() = nodes * validatorsPerNode

    init {
        require(nodes > 0) { "population.nodes must be > 0, got $nodes" }
        require(validatorsPerNode >= 0) { "population.validatorsPerNode must be >= 0" }
        require(subnetsPerNode in 1..subnetCount) {
            "population.subnetsPerNode must be in [1, $subnetCount], got $subnetsPerNode"
        }
    }

    fun build(): DcNetwork<ContinentRegion> =
        DcNetworkBuilder.world(randomSeed = seed, subnetCount = subnetCount)
            .addGroup(count = nodes) {
                spreadOverRegions()
                bandwidth = this@DcPopulationConfig.bandwidth.value
                validators = validatorsPerNode
                peers = this@DcPopulationConfig.peers
                randomSubnets(count = subnetsPerNode)
            }
            .build()
}

/** Named link rates, so YAML says `bandwidth: residential` rather than a byte count. */
enum class DcBandwidthPreset(val value: Bandwidth) {
    RESIDENTIAL(Bandwidths.RESIDENTIAL),
    VPS(Bandwidths.VPS),
    DATACENTER(Bandwidths.DATACENTER);

    companion object {
        fun of(name: String): DcBandwidthPreset =
            values().firstOrNull { it.matches(name) }
                ?: throw IllegalArgumentException(
                    "Unknown bandwidth '$name', expected one of ${values().joinToString { it.name.lowercase() }}"
                )
    }
}

/** Which validators attest, and when. */
data class DcAttestationRunConfig(
    val mode: DcAttesterMode = DcAttesterMode.ALL_VALIDATORS,
    /** Only used when [mode] is [DcAttesterMode.SAMPLE]. */
    val attestersPerWave: Int = 32,
    val waveCount: Int = 1,
    val sizeBytes: Int = 240,
    val warmup: Duration = 60.seconds,
    val waveInterval: Duration = 12.seconds,
    val settle: Duration = 30.seconds
) {
    init {
        require(waveCount > 0) { "attestation.waveCount must be > 0, got $waveCount" }
        require(sizeBytes >= DcAttestationNodeProgram.HEADER_BYTES) {
            "attestation.sizeBytes must be >= ${DcAttestationNodeProgram.HEADER_BYTES}, got $sizeBytes"
        }
    }

    fun toScenarioConfig(seed: Long, gossipParams: GossipParams = GossipParams()): DcAttestationConfig =
        DcAttestationConfig(
            waveCount = waveCount,
            attestersPerWave = attestersPerWave,
            attestationSizeBytes = sizeBytes,
            warmup = warmup,
            waveInterval = waveInterval,
            settle = settle,
            gossipParams = gossipParams,
            randomSeed = seed
        )
}

enum class DcAttesterMode {
    /** Every validator in the network attests in every wave. */
    ALL_VALIDATORS,

    /** A random sample of `attestersPerWave` nodes attests, one attestation each. */
    SAMPLE;

    companion object {
        fun of(name: String): DcAttesterMode =
            values().firstOrNull { it.matches(name) }
                ?: throw IllegalArgumentException(
                    "Unknown attester mode '$name', expected one of " +
                        values().joinToString { it.name.lowercase() }
                )
    }
}

/**
 * Matches an enum constant against a YAML spelling, ignoring case and word separators, so that
 * `allValidators`, `all_validators` and `ALL-VALIDATORS` all name the same thing. YAML files
 * conventionally use camelCase while Kotlin enums use SCREAMING_SNAKE; neither should have to win.
 */
private fun Enum<*>.matches(text: String): Boolean =
    name.normalizedEnumName() == text.normalizedEnumName()

private fun String.normalizedEnumName(): String =
    lowercase().replace("_", "").replace("-", "").replace(" ", "")

/**
 * Gossipsub tuning knobs for the attestation scenario, one field per [GossipParams] constructor
 * argument (its callback-typed `connectCallback` aside, which has no place in a data config).
 *
 * Every field defaults to `null`, meaning "leave it at the Gossip 1.1 default". This matters beyond
 * saving typing: [GossipParams.builder] derives [DLow]/[DHigh]/[DScore]/[DOut] from [D] when they are
 * left unset, so overriding only [D] rescales the whole mesh consistently rather than leaving the
 * other bounds stuck at the defaults for `D = 6`. Set the derived fields explicitly to opt out of
 * that derivation.
 */
data class DcGossipConfig(
    val D: Int? = null,
    val DLow: Int? = null,
    val DHigh: Int? = null,
    val DScore: Int? = null,
    val DOut: Int? = null,
    val DLazy: Int? = null,
    val fanoutTTL: Duration? = null,
    val maxGossipMessageSize: Int? = null,
    val gossipSize: Int? = null,
    val gossipHistoryLength: Int? = null,
    val heartbeatInterval: Duration? = null,
    val seenTTL: Duration? = null,
    val floodPublishMaxMessageSizeThreshold: Int? = null,
    val gossipFactor: Double? = null,
    val opportunisticGraftPeers: Int? = null,
    val opportunisticGraftTicks: Int? = null,
    val graftFloodThreshold: Duration? = null,
    val maxPublishedMessages: Int? = null,
    val maxTopicsPerPublishedMessage: Int? = null,
    val maxSubscriptions: Int? = null,
    val maxIHaveLength: Int? = null,
    val maxIHaveMessages: Int? = null,
    val maxIWantMessageIds: Int? = null,
    val iWantFollowupTime: Duration? = null,
    val maxGraftMessages: Int? = null,
    val maxPeersSentInPruneMsg: Int? = null,
    val maxPeersAcceptedInPruneMsg: Int? = null,
    val pruneBackoff: Duration? = null,
    val maxPruneMessages: Int? = null,
    val gossipRetransmission: Int? = null,
    val maxIDontWantMessageIds: Int? = null,
    val iDontWantMinMessageSizeThreshold: Int? = null,
    val iDontWantTTL: Duration? = null
) {
    /**
     * Resolves overrides against [GossipParams]' own defaults and derivations. Validation (e.g.
     * `DOut <= D / 2`) is [GossipParams]'s, so a bad combination surfaces here rather than being
     * re-checked ahead of time.
     */
    fun toGossipParams(): GossipParams {
        val builder = GossipParams.builder()
        D?.let { builder.D(it) }
        DLow?.let { builder.DLow(it) }
        DHigh?.let { builder.DHigh(it) }
        DScore?.let { builder.DScore(it) }
        DOut?.let { builder.DOut(it) }
        DLazy?.let { builder.DLazy(it) }
        fanoutTTL?.let { builder.fanoutTTL(it.toJavaDuration()) }
        maxGossipMessageSize?.let { builder.maxGossipMessageSize(it) }
        gossipSize?.let { builder.gossipSize(it) }
        gossipHistoryLength?.let { builder.gossipHistoryLength(it) }
        heartbeatInterval?.let { builder.heartbeatInterval(it.toJavaDuration()) }
        seenTTL?.let { builder.seenTTL(it.toJavaDuration()) }
        floodPublishMaxMessageSizeThreshold?.let { builder.floodPublishMaxMessageSizeThreshold(it) }
        gossipFactor?.let { builder.gossipFactor(it) }
        opportunisticGraftPeers?.let { builder.opportunisticGraftPeers(it) }
        opportunisticGraftTicks?.let { builder.opportunisticGraftTicks(it) }
        graftFloodThreshold?.let { builder.graftFloodThreshold(it.toJavaDuration()) }
        maxPublishedMessages?.let { builder.maxPublishedMessages(it) }
        maxTopicsPerPublishedMessage?.let { builder.maxTopicsPerPublishedMessage(it) }
        maxSubscriptions?.let { builder.maxSubscriptions(it) }
        maxIHaveLength?.let { builder.maxIHaveLength(it) }
        maxIHaveMessages?.let { builder.maxIHaveMessages(it) }
        maxIWantMessageIds?.let { builder.maxIWantMessageIds(it) }
        iWantFollowupTime?.let { builder.iWantFollowupTime(it.toJavaDuration()) }
        maxGraftMessages?.let { builder.maxGraftMessages(it) }
        maxPeersSentInPruneMsg?.let { builder.maxPeersSentInPruneMsg(it) }
        maxPeersAcceptedInPruneMsg?.let { builder.maxPeersAcceptedInPruneMsg(it) }
        pruneBackoff?.let { builder.pruneBackoff(it.toJavaDuration()) }
        maxPruneMessages?.let { builder.maxPruneMessages(it) }
        gossipRetransmission?.let { builder.gossipRetransmission(it) }
        maxIDontWantMessageIds?.let { builder.maxIDontWantMessageIds(it) }
        iDontWantMinMessageSizeThreshold?.let { builder.iDontWantMinMessageSizeThreshold(it) }
        iDontWantTTL?.let { builder.iDontWantTTL(it.toJavaDuration()) }
        return builder.build()
    }

    /** Lists only the overridden fields, so a scenario's `describe()` stays quiet when unused. */
    fun describe(): String {
        val overrides = buildList {
            D?.let { add("D=$it") }
            DLow?.let { add("DLow=$it") }
            DHigh?.let { add("DHigh=$it") }
            DScore?.let { add("DScore=$it") }
            DOut?.let { add("DOut=$it") }
            DLazy?.let { add("DLazy=$it") }
            fanoutTTL?.let { add("fanoutTTL=$it") }
            maxGossipMessageSize?.let { add("maxGossipMessageSize=$it") }
            gossipSize?.let { add("gossipSize=$it") }
            gossipHistoryLength?.let { add("gossipHistoryLength=$it") }
            heartbeatInterval?.let { add("heartbeatInterval=$it") }
            seenTTL?.let { add("seenTTL=$it") }
            floodPublishMaxMessageSizeThreshold?.let { add("floodPublishMaxMessageSizeThreshold=$it") }
            gossipFactor?.let { add("gossipFactor=$it") }
            opportunisticGraftPeers?.let { add("opportunisticGraftPeers=$it") }
            opportunisticGraftTicks?.let { add("opportunisticGraftTicks=$it") }
            graftFloodThreshold?.let { add("graftFloodThreshold=$it") }
            maxPublishedMessages?.let { add("maxPublishedMessages=$it") }
            maxTopicsPerPublishedMessage?.let { add("maxTopicsPerPublishedMessage=$it") }
            maxSubscriptions?.let { add("maxSubscriptions=$it") }
            maxIHaveLength?.let { add("maxIHaveLength=$it") }
            maxIHaveMessages?.let { add("maxIHaveMessages=$it") }
            maxIWantMessageIds?.let { add("maxIWantMessageIds=$it") }
            iWantFollowupTime?.let { add("iWantFollowupTime=$it") }
            maxGraftMessages?.let { add("maxGraftMessages=$it") }
            maxPeersSentInPruneMsg?.let { add("maxPeersSentInPruneMsg=$it") }
            maxPeersAcceptedInPruneMsg?.let { add("maxPeersAcceptedInPruneMsg=$it") }
            pruneBackoff?.let { add("pruneBackoff=$it") }
            maxPruneMessages?.let { add("maxPruneMessages=$it") }
            gossipRetransmission?.let { add("gossipRetransmission=$it") }
            maxIDontWantMessageIds?.let { add("maxIDontWantMessageIds=$it") }
            iDontWantMinMessageSizeThreshold?.let { add("iDontWantMinMessageSizeThreshold=$it") }
            iDontWantTTL?.let { add("iDontWantTTL=$it") }
        }
        return if (overrides.isEmpty()) "defaults" else overrides.joinToString(", ")
    }
}

data class DcRunSettings(
    val seed: Long = 1,
    val minPeersPerSubnet: Int = 2,
    val latencyWindowParallelism: Int = 8,
    /** Where the CSV summary goes, relative to the module directory. */
    val outputDir: String = "build/dc-reports"
)
