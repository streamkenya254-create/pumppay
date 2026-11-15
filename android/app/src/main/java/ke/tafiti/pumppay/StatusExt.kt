package ke.tafiti.pumppay

/** The pump is cut off because the token ran out - not because it was switched off from the app. */
val Status.tokenLocked: Boolean get() = relayLocked && !manualOff

/**
 * The line carries no current and is not about to: nothing is drawing from it.
 *
 * A stopped pump and an unplugged one look the same from here - both read about
 * zero amps - so this is worded as "not connected" only on the strength of
 * having no load at all, never of a run that stopped a moment ago.
 */
fun Status.noLoad(): Boolean = aOk && amps < 0.1 && state != "NO SIGNAL" && !relayLocked
