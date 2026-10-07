package xyz.mdhv.asom.lab.proto.integration

/** What L-L13 exercised over real TLS. Every counter that the law lists must be non-zero at the end of the run (non-vacuity). */
class L13Counts {
    var runsWithFailure = 0
    var failureEvents = 0
    var controlFailures = 0
    var requesterIntentFailures = 0
    var lenderIntentFailures = 0
    var lenderEndOutcomeFailures = 0
    var lenderDeclineOutcomeFailures = 0
    var dialFailures = 0
    var framesAfterControlFailure = 0
    var contentFramesAfterStickyFailure = 0
    var tapChecked = 0
}
