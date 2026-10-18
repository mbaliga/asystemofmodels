package xyz.mdhv.asom.ledger

import xyz.mdhv.asom.contract.CostBasis
import xyz.mdhv.asom.contract.Egress
import xyz.mdhv.asom.contract.RouteRecord

/** Pure mapping between the contract's [RouteRecord] and the Room row (§1.9). */
fun RouteRecord.toEntity(): RouteLogEntity = RouteLogEntity(
    ts = ts,
    callerPkg = callerPkg,
    requestedModel = requestedModel,
    servedProvider = servedProvider,
    servedModel = servedModel,
    egress = egress.wire,
    bytesOut = bytesOut,
    tokensIn = tokensIn,
    tokensOut = tokensOut,
    costEst = costEst,
    costBasis = costBasis.wire,
    latencyMs = latencyMs,
    status = status,
)

fun RouteLogEntity.toRecord(): RouteRecord = RouteRecord(
    ts = ts,
    callerPkg = callerPkg,
    requestedModel = requestedModel,
    servedProvider = servedProvider,
    servedModel = servedModel,
    egress = Egress.entries.first { it.wire == egress },
    bytesOut = bytesOut,
    tokensIn = tokensIn,
    tokensOut = tokensOut,
    costEst = costEst,
    costBasis = CostBasis.entries.first { it.wire == costBasis },
    latencyMs = latencyMs,
    status = status,
)
