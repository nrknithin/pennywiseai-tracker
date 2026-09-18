package com.pennywiseai.tracker.data.email

import com.pennywiseai.parser.core.email.SenderMapping

/**
 * The accepted `sender_overrides` rows, in the two shapes the email channel consumes them in
 * (spec 11 §11.6).
 *
 * **Why an interface and not the repository.** These are the only two questions the sync path
 * asks about overrides, and asking them through a two-method seam is what lets
 * `EmailSyncRunner` and `IngestingEmailBatchProcessor` keep being constructed in a JVM unit
 * test with no Room — which is the property their whole test suites rest on. [NONE] is the
 * honest default for those tests: no override exists, so the registry alone decides, which is
 * exactly what both classes did before this table existed.
 *
 * The two methods are separate because they are used at different moments and one is much
 * more expensive to get wrong. [searchTerms] widens the server-side `OR(FROM …)` of R-11-03,
 * so a sender missing from it is mail that is never downloaded at all; [mappings] only
 * decides how mail already in hand is labelled and routed.
 */
interface SenderOverrides {

    /**
     * Keyed by address or domain, as `EmailSenderRegistry.resolve` expects.
     *
     * Read once per batch rather than cached for the run, so a sender the owner accepts while
     * a sync is in flight is honoured by the next batch instead of the next run.
     */
    suspend fun mappings(): Map<String, SenderMapping>

    /** The terms an accepted override adds to R-11-03's server-side `OR(FROM …)` search. */
    suspend fun searchTerms(): List<String>

    companion object {

        /**
         * No overrides: the compiled registry alone.
         *
         * Not a stub for production — it is the correct answer for a device where the owner
         * has accepted nothing, and it is what every test that is not about overrides should
         * be handed.
         */
        val NONE: SenderOverrides = object : SenderOverrides {
            override suspend fun mappings(): Map<String, SenderMapping> = emptyMap()
            override suspend fun searchTerms(): List<String> = emptyList()
        }
    }
}
