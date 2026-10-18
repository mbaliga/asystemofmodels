package xyz.mdhv.asom.desktop.win.net

import java.net.InetAddress
import xyz.mdhv.asom.desktop.ListenerGate
import xyz.mdhv.asom.desktop.ListenerGateDecision
import xyz.mdhv.asom.desktop.win.api.InterfaceLister

sealed interface ListenDecision {
    /** May bind exactly [address] (the selected interface's address) and no other. */
    class MayBind(val address: InetAddress, val firewallRule: String) : ListenDecision
    class Closed(val state: String, val detail: String) : ListenDecision
}

/**
 * The seam's [ListenerGate] on Windows (C16): closed unless an interface was selected AND is still eligible AND the
 * firewall gate finds asom's own allow rule and no block rule. The interface check comes first, so with nothing
 * selected the firewall is not even read. Nothing listens in this module; the gate is what a later listener asks.
 */
class WinListenerGate(
    private val selection: () -> SelectedInterface?,
    private val lanConfirmed: () -> Set<String>,
    private val interfaces: InterfaceLister,
    private val firewall: FirewallGate,
) : ListenerGate {
    fun evaluate(): ListenDecision {
        val nics = try {
            interfaces.list()
        } catch (e: Exception) {
            return ListenDecision.Closed("INTERFACES_UNREADABLE", e.message ?: e::class.simpleName.orEmpty())
        }
        val address = when (val e = InterfaceEligibility.check(selection(), nics, lanConfirmed())) {
            is Eligibility.Ineligible -> return ListenDecision.Closed(e.reason.name, e.detail)
            is Eligibility.Eligible -> e.address
        }
        return when (val g = firewall.evaluate()) {
            is GateResult.Open -> ListenDecision.MayBind(address, g.allowRule)
            is GateResult.BlockRulePresent -> ListenDecision.Closed(g.stateName, g.ruleNames.joinToString())
            is GateResult.ProbeFailed -> ListenDecision.Closed(g.stateName, g.reason)
            GateResult.RuleMissing -> ListenDecision.Closed(g.stateName, "no asom allow rule; run the printed command (asom mesh firewall print)")
        }
    }

    override fun decision(): ListenerGateDecision =
        if (evaluate() is ListenDecision.MayBind) ListenerGateDecision.OPEN else ListenerGateDecision.CLOSED_UNTIL_CONSENT
}

/** Holds what `asom mesh listen` selected. In this scaffold nothing sets it, so a fresh node's gate is closed. */
class ListenSelection {
    @Volatile
    var selected: SelectedInterface? = null

    @Volatile
    var lanConfirmed: Set<String> = emptySet()
}
