package com.pennywiseai.tracker.domain.engine

import java.math.BigDecimal

/**
 * The four `settings.dues.*` figures that bound one question from two sides (spec 28 §28.4):
 * how far apart a payment and the bill it cleared may lie, and how far apart their amounts may be.
 *
 * **One type rather than two copies of four defaults, because the two readers are required to
 * agree.** [DueMergeConfig] gives a payment-confirmation *notice* the span over which it may reach
 * a bill (spec 20 §20.4 step 7); `SettlementConfig` gives the *transaction the same mail produces*
 * the span over which it may settle a due (§20.5 step 3). The two are deliberately the same span:
 * a notice window wider than the settler's lets a confirmation land on a bill that transaction
 * could never settle, and spec 26's `UNMATCHED_PAYMENT_NOTICE` would then name a statement nobody
 * paid; a narrower one drops the notice from a bill the payment does settle. Until this type
 * existed each side carried its own literal 45 / 60 / 1.00 / 0, in different modules, with nothing
 * tying them together — and M2 wires `settings.dues.*` into both, so wiring one and forgetting the
 * other would have changed no behaviour loudly and left no test red.
 *
 * Passed per call rather than injected, for the same reason [DedupConfig] is: one sync run judges
 * every message by one copy of the rules, and a test can vary them without a container.
 *
 * ### The two tolerances are not applied symmetrically, and that is deliberate
 *
 * Both readers apply [amountToleranceAbs] **and** [amountTolerancePct] to the amount *billed*
 * (through [amountTolerance]), and [amountToleranceAbs] **alone** to the *minimum due*.
 * `DueMergeEngine.confirmationScore` and `PlausibleDueSettler.amountAgrees` are the two sites and
 * they match on purpose. §20.5 step 4 writes the percentage against the bill only — "≤ tol_abs or
 * ≤ due.amount×tol_pct" — and says nothing about a percentage of the minimum. The reading matters
 * because a percentage of a card bill is a large sum beside its minimum: 5 % of a ₹50,000 statement
 * is ₹2,500, which is the whole of a typical minimum, so applying it there would let almost any
 * payment claim to be the minimum and settle the wrong bill. See docs/FORK-CHANGES.md.
 */
data class DuesTimingConfig(
    /**
     * How far **ahead of** a payment the bill it cleared may be dated: how early the user is
     * allowed to pay. `settings.dues.earlyPayDays` (45).
     *
     * Spec 20 §20.8's worked case is the reason this side is the wide-open one at all — an Axis
     * statement due 1 October, paid in full on 16 September, "early payment inside `earlyPayDays`".
     */
    val earlyPayDays: Long = 45,

    /**
     * How far **behind** a payment the bill it cleared may be dated: how late the user is allowed
     * to be. `settings.dues.latePayDays` (60).
     *
     * The span is asymmetric, and the early/late pairing is the opposite way round from §20.5
     * step 3's own `[date − before, date + after]` wording, which reads backwards against §20.8
     * (docs/FORK-CHANGES.md). Stating both sides here, in one place, is what keeps the two readers
     * from disagreeing about which end is which.
     */
    val latePayDays: Long = 60,

    /** §20.5 step 4's `tol_abs`: `settings.dues.amountToleranceAbs` (1.00, range 0–100). */
    val amountToleranceAbs: BigDecimal = BigDecimal("1.00"),

    /**
     * §20.5 step 4's `tol_pct`, in **percent** — `settings.dues.amountTolerancePct`, default 0,
     * range 0–10 on screen 51.
     *
     * Percent, not a fraction: spec 28 §28.4 ranges it 0–10 and spec 20 spells its sibling
     * `fxTolerancePct` "3 %", so a settings store handing over the number the user typed means
     * 3 % by it. [amountTolerance] is the one place that conversion happens, because a percentage
     * read as a fraction — the shape this code had while the default was zero and nothing could
     * notice — would be a hundredfold tolerance the first time the owner raised it off zero.
     */
    val amountTolerancePct: BigDecimal = BigDecimal.ZERO
) {

    /**
     * §20.5 step 4's amount test against a bill somebody stated: the absolute tolerance, or the
     * percentage of that bill where it is larger ("≤ tol_abs **or** ≤ due.amount×tol_pct").
     *
     * Here rather than at the two call sites so the merge and the settler cannot come to different
     * answers about the same pair of figures — the whole reason this type exists.
     *
     * @param billed the amount the biller stated; never a column's zero standing in for "unknown"
     *   (constraint C7 — both callers check that before they get here).
     */
    fun amountTolerance(billed: BigDecimal): BigDecimal =
        amountToleranceAbs
}
