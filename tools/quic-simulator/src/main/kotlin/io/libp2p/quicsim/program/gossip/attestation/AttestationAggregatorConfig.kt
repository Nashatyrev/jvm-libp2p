package io.libp2p.quicsim.program.gossip.attestation

data class AttestationAggregatorConfig(
    val aggregatorId: String,
    val distribution: AttestationArrivalDistribution,
    val rule: AggregatePublishRule,
) {
    init {
        require(aggregatorId.isValidAggregateField()) {
            "aggregatorId must not be blank or contain tabs/new lines"
        }
    }
}
