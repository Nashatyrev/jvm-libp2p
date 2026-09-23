package io.libp2p.example.dc

/**
 * What a run's gossipsub control traffic was actually spent on.
 *
 * The aggregate `control` figure lumps together costs that scale with completely different things,
 * which makes it easy to misread: IHAVE/IWANT scale with the number of *messages* published (each
 * announced id costs the same whether it stands for a 240 B attestation or a 128 KiB blob),
 * GRAFT/PRUNE with mesh churn, and subscriptions are paid once per topic when a peer connects. A
 * scenario that halves its message count by doubling message size leaves publish bytes alone and
 * takes IHAVE with it — so knowing the split is what tells you whether a change will help.
 *
 * [framingBytes] is the remainder: the RPC's own length prefix and the tag/length bytes of the
 * `control` wrapper, i.e. everything the parts above do not name.
 */
data class DcControlBreakdown(
    val subscriptionBytes: Long,
    val ihaveBytes: Long,
    val iwantBytes: Long,
    val graftBytes: Long,
    val pruneBytes: Long,
    val idontwantBytes: Long,
    /** Message ids announced in IHAVE, the quantity IHAVE cost is proportional to. */
    val ihaveMessageIds: Long,
    /** Message ids requested in IWANT, i.e. how many announcements led to an actual fetch. */
    val iwantMessageIds: Long,
    /** Message ids carried in IDONTWANT, i.e. how many "stop sending me this" signals were spent. */
    val idontwantMessageIds: Long,
    val totalBytes: Long
) {
    val framingBytes: Long
        get() = totalBytes - subscriptionBytes - ihaveBytes - iwantBytes - graftBytes - pruneBytes - idontwantBytes

    operator fun plus(other: DcControlBreakdown) = DcControlBreakdown(
        subscriptionBytes = subscriptionBytes + other.subscriptionBytes,
        ihaveBytes = ihaveBytes + other.ihaveBytes,
        iwantBytes = iwantBytes + other.iwantBytes,
        graftBytes = graftBytes + other.graftBytes,
        pruneBytes = pruneBytes + other.pruneBytes,
        idontwantBytes = idontwantBytes + other.idontwantBytes,
        ihaveMessageIds = ihaveMessageIds + other.ihaveMessageIds,
        iwantMessageIds = iwantMessageIds + other.iwantMessageIds,
        idontwantMessageIds = idontwantMessageIds + other.idontwantMessageIds,
        totalBytes = totalBytes + other.totalBytes
    )

    /** One line per run or group: each part as a share of [totalBytes], largest first. */
    fun render(nodeCount: Int, label: String = "gossip control breakdown"): String {
        val parts = listOf(
            "ihave" to ihaveBytes,
            "iwant" to iwantBytes,
            "graft" to graftBytes,
            "prune" to pruneBytes,
            "idontwant" to idontwantBytes,
            "subs" to subscriptionBytes,
            "framing" to framingBytes
        ).sortedByDescending { it.second }
        val shares = parts.joinToString(" ") { (name, bytes) ->
            "$name=%.1f%%".format(if (totalBytes == 0L) 0.0 else bytes * 100.0 / totalBytes)
        }
        return "$label: %.0f B/node %s; ids announced/node=%.0f requested/node=%.0f idontwant/node=%.0f"
            .format(
                totalBytes.toDouble() / nodeCount,
                shares,
                ihaveMessageIds.toDouble() / nodeCount,
                iwantMessageIds.toDouble() / nodeCount,
                idontwantMessageIds.toDouble() / nodeCount
            )
    }

    companion object {
        val EMPTY = DcControlBreakdown(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}
