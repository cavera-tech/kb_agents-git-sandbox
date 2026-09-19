# Functional specification — Report of trades past their settlement date that are still unsettled

Ticket: **#6**

## 1. Purpose

Provide an operations desk with a **read-only** report that lists the trades whose
settlement date has already passed but which have **not yet been settled**, so the
desk can see who it needs to chase.

The report is grouped **by counterparty**. For every trade it shows the trade
identifier, the counterparty, the instrument, the settlement date, how many days
it is overdue, and the amount still outstanding with its currency. For every
counterparty it shows a count of unsettled trades and a total outstanding amount,
and the counterparty groups are ordered by that total, largest first.

This document describes the behaviour, the rules, the edge cases, and the boundary
of the work. It does not describe an implementation.

## 2. Target system and the data it reads from

The system of record is **Openlink Findur / Endur ETRM**. Trades live in the
`ab_tran` table (the trade/transaction table), with counterparty, settlement date,
amount and currency recorded on the trade row, and reference data (currency,
business unit) in the static-data tables. (Source: `Trading Manager →
Transaction Listing`; `OpenJVS Programmers Guide`, which reads directly from
`ab_tran`; `Administration Manager → Currencies`.)

A single trade is identified by two numbers in the system:

- **Transaction number (`tran_num`)** — one per transaction. This is the
  per-trade identifier used as the report row key.
  (Source: `TPM Step Guide → Get Tran Info`, "Tran Num … the desired transaction
  number"; `Trading Manager → Transaction Status`.)
- **Deal number (`deal_num`)** — a stable number shared by the original trade and
  every amendment/buyout derived from it. Used for tracing, not for the row key.
  (Source: `Liquid and Bulk APM Package User Guide`, "Deal Num … remains constant
  for the life of the deal"; `Trading Manager → Transaction Status`.)

### Field-to-report mapping

| Report item | System field | Note / source |
|---|---|---|
| Trade identifier | `tran_num` | Per-transaction number (`TPM Step Guide`; `Transaction Status`) |
| Deal (trace) | `deal_num` | Stable across amendments/buyouts (`APM Package User Guide`) |
| Counterparty | `ab_tran.external_bunit` (Ext Bunit / "Ctp Unit") | The business unit of the counterparty; display name taken from the business-unit reference. Source: `Trading Toolsets → Provisional` / `Swaption` ("Unit: the business unit of the counterparty … `ab_tran.external_bunit`"); `BTC Gateway User Guide` ("Ext Bunit → PartyID"); `Settlements Payment Entry` ("ext_bunit: the name of the counter party's business unit"). |
| Instrument | *not pinned down — see Open Question 5* | The trade's instrument reference (to be confirmed against the `ab_tran` schema). |
| Settlement date | `settle_date` | "The date the deal settles." (`Transaction Listing`) |
| Amount outstanding | `position` | "The remaining portion of the deal after a specific event has been executed." (`Transaction Listing`). Exact definition under partial settlement — see Open Question 2. |
| Currency | `ab_tran.currency` (id) → `currency.name` | `currency` stores the id_number of the currency (`Transaction Listing`); the three-letter code is `currency.name` (`Administration Manager → Currencies`). |
| Trade status | `tran_status` | The trade lifecycle status (see §3 and Open Question 1). |

## 3. What counts as "unsettled"

The report is about trades that are **still owed**. In the system's own terms a
trade has a **lifecycle status** (`tran_status`) and separately carries a
**remaining amount** (`position`). The KB confirms the lifecycle vocabulary
includes `PROPOSED`, `NEW`, `PENDING`, `VALIDATED`, `MATURED`, `CANCELLED`,
`BUYOUT`, `AMENDED` (and the "*New" variants `AMENDED NEW`, `BUYOUT NEW`,
`CANCELLED NEW`). (Source: `TOF Gateway User Guide`, "valid string values … 'NEW',
'VALIDATED', 'PENDING' and 'PROPOSED'"; `ICE ECMS and TradeVault Installation
Guide`, the full status map; `Trading Manager → Transaction Status`.)

The KB also shows that **settlement is a distinct lifecycle from the trade
lifecycle**: the system records settlement activity with its own statuses (e.g.
settlement events/documents such as `pending`, `completed`, `cancelled`,
`not_processed`; and *settlement instruction* records with `Authorized`, `Pending`,
`Do Not Use`, `Template`). (Source: `Dashboard Standard Content v1.1`;
`Reference Explorer → Settlement Instruction Editor`, `settlement_instruction.settle_status`.)
Note this `settle_status` is on the **settlement instruction** record — a different
object from the trade's own settlement state, and not to be conflated with it.

**Business rule — a trade appears in the report when, as of the report's business
date, all of the following hold:**

1. **Its settlement date is past.** `settle_date` is strictly before the report's
   business date. This is the "past its settlement date" condition and it drives
   the "days overdue" figure (§4).
2. **It is still a live obligation** — it has not been cancelled, bought out
   (terminated), or superseded by an amendment. Concretely, the report **excludes**
   trades whose status is a terminal/void state: `CANCELLED`, `BUYOUT`, the
   superseded-original `AMENDED`, and `DELETED`/`TEMPLATE` rows. (Source: `DTCC/SDR
   Gateway User Guide`, "Cancelled … within Findur", "Buyout … within Findur";
   `Connex MTM Gateway User Guide`, "A Full Termination … is a Buyout/Cancellation",
   "A Partial Termination … is an Amendment".)
3. **It still has a remaining amount outstanding** — it has not been fully settled.
   A fully-settled trade carries a zero remaining amount, so it drops out of rule 3
   on its own. ("Partial" settlement is a real state in this system — `Settlements
   Payment Entry`, `Suggested Amount To Be Applied = Settle Amount minus Previously
   Applied Amount`; `DTCC/SDR Gateway User Guide`, "Partial Buyout"/"Partial
   Novation".)

Anchor note. Rule 3 deliberately grounds "unsettled" on an **observable** (the
trade still carries a remaining amount) rather than asserting a single status field
that this KB does not surface. **The exact field that records whether a trade's
settlement is complete, and the exact value(s) that mean "not yet settled" vs
"settled" vs "partially settled", are not documented in the KB** — see **Open
Question 1**. If an explicit trade-level settlement-status field exists, rule 3
should additionally require it not to be a fully-settled value.

## 4. The run and its date

- The report is **run on demand** for a **chosen business date**. If no date is
  supplied it **defaults to today**. (Ticket; consistent with the system's own
  reporting convention — `HedgePak Standard Reports`: "if no as-of-date reference
  is specified, the report date defaults to the last good business day prior to the
  Current Date", with `AS_OF_REFERENCE` accepting `today` / `yesterday` / `lgbd`.)
- The system's "current date" is driven by the **Business Date** setting maintained
  in the **System Date Roll** feature of the Operations Manager. (Source: `Trading
  Toolsets → DIGOPT`, "the current date is initialized by the Business Date setting
  defined in the System Date Roll feature in the Operations Manager".)
- **Days overdue** for a trade = the report's business date minus the trade's
  `settle_date`. The KB shows the system supports **both** calendar-day and
  business-day day-counts (`DTCC/SDR Gateway User Guide`, "Business … count includes
  only business days. Calendar … includes all calendar days"). **Which convention to
  use for "days overdue" is an open question** (Open Question 6); the natural
  default for an aging/overdue report is calendar days.
- The report is **read-only**: it selects and presents. It **does not** change a
  trade, set a status, mark a chase, or write anything back. (Ticket.)

## 5. Output and presentation

The report is a **listing grouped by counterparty**:

- **Per trade (one row per reportable trade, keyed by `tran_num`):**
  - Trade identifier — `tran_num` (with `deal_num` available for tracing the
    amendment/buyout chain).
  - Counterparty — the name behind `external_bunit`.
  - Instrument — the trade's instrument (see Open Question 5).
  - Settlement date — `settle_date`.
  - Days overdue — business date minus `settle_date` (see §4, Open Question 6).
  - Amount outstanding — `position`, with its **currency** (`currency.name`).
- **Per counterparty (group header / total):**
  - **Count** — number of unsettled trades for that counterparty.
  - **Total outstanding** — sum of the outstanding amounts for that counterparty
    (**see the multi-currency edge case, Open Question 3** — a single total is only
    meaningful once a per-currency basis or a reporting currency is agreed).
- **Ordering:** counterparty groups sorted by **total outstanding, largest first**.
  Suggested tie-break (to confirm): higher count first, then counterparty name —
  see Open Question 7.

## 6. Edge cases

1. **Cancelled after settlement date.** A trade cancelled (status `CANCELLED`) no
   longer owes settlement and is **excluded**, even if its original settlement date
   is in the past. The trade-level `tran_status` `CANCELLED` (and the "*New"
   cancelled state) mark this. (Source: `DTCC/SDR Gateway User Guide`, `ICE ECMS
   and TradeVault Installation Guide` status map.)
2. **Amended after settlement date.** An amendment does **not** modify the original
   trade in place. "The original trade is retained for audit purposes and a brand
   new trade with a new transaction number (but same deal number) is created" — the
   original moves to `AMENDED` and the replacement starts life as `AMENDED NEW` and
   proceeds to `VALIDATED`/`MATURED`. (Source: `Trading Manager → Transaction
   Status`.) Therefore the report must show **only the current transaction**
   (the replacement with a live status and a remaining amount), and **exclude the
   superseded original** (`AMENDED`). A trade that was amended *after* its settlement
   date is reported on the basis of the **current** trade's settlement date and
   remaining amount.
3. **Bought out / terminated after settlement date.** A full buyout/termination sets
   the trade to `BUYOUT` and the outstanding is extinguished → **excluded**. A
   **partial** buyout leaves a remaining amount; that remaining amount stays
   reportable as long as the trade is live and past its settlement date. (Source:
   `Connex MTM Gateway User Guide`, `DTCC/SDR Gateway User Guide` "Partial Buyout".)
4. **Partially settled.** A trade that has only been partially settled still has a
   remaining amount, so it **remains in the report** with that remaining amount as
   the outstanding figure (exact field — Open Question 2).
5. **Matured trade.** The relationship between `MATURED` and settlement completion
   is not stated in the KB. A matured-and-fully-settled trade is excluded by rule 3
   (no remaining amount); a matured trade that still owes money must remain.
   **Confirm what `MATURED` implies about settlement in this deployment** — Open
   Question 4.
6. **Not-yet-validated trades.** Whether `PROPOSED` / `NEW` / `PENDING` trades (not
   yet a firm, accepted obligation) should be chased is a business decision — Open
   Question 8. The safe reading for a chase report is to restrict to trades that are
   a live obligation (`VALIDATED` and beyond), but this is not stated in the ticket.
7. **Multi-currency group total.** A counterparty may hold unsettled trades in more
   than one currency. A single "total per group" is only valid **per currency** or
   after conversion to a **reporting currency**. This is a genuine business decision
   the ticket does not resolve — **Open Question 3** (recommended default: present
   the group total broken down by currency, and/or add a reporting-currency
   conversion if FX rates are available).
8. **No counterparty / internal trade.** If `external_bunit` is empty (an internal
   or proprietary trade), decide whether to exclude it or show it in an explicit
   "no counterparty / internal" group — **Open Question 9**.
9. **Multi-leg trades.** If a trade is recorded as multiple legs, decide whether the
   report is one row per trade (aggregating legs) or one row per leg, since this
   affects the per-counterparty count and total — **Open Question 10**.
10. **Empty result.** If no trade is past its settlement date and unsettled, the
    report is a valid, zero-row result (no groups, no totals). It should not be an
    error.
11. **Overdue by zero.** A trade whose settlement date **equals** the business date
    is **not yet overdue** and is excluded by rule 1 (settlement date must be
    *strictly before* the business date).

## 7. Out of scope

Per the ticket, and confirmed here:

- No chasing workflow, tasking, or escalation.
- No notifications (email, messages, reminders).
- No write-back of any kind — the report never changes a trade, a status, or a
  chase flag.
- No change to how settlement status is recorded or to the settlement process.
- Producing the report on a schedule is **not** asked for; it is run **on demand**.

## 8. Open questions

These are points the KB does not settle and which would change the result if
answered differently. They are raised rather than assumed.

1. **The trade-level settlement-status field and its "unsettled" value.** The KB
   documents the trade lifecycle (`tran_status`) and the *settlement-instruction*
   status (`settlement_instruction.settle_status`: `Authorized`, `Pending`,
   `Do Not Use`, `Template`), but it does **not** surface a single field on the
   trade that flips it to "settled", nor the exact value(s) meaning
   "not yet settled" / "settled" / "partially settled". This spec therefore
   anchors "unsettled" on the remaining-amount rule (§3, rule 3). **Confirm the
   exact field and value(s)** against the `ab_tran` schema / Findur data
   dictionary, and whether rule 3 should additionally test it.
2. **Exact "amount outstanding" field.** The KB defines `position` as "the
   remaining portion of the deal". Given partial settlement is real, confirm
   whether the outstanding figure is `position` directly or `position` minus a
   settled/applied amount, and which field holds it.
3. **Multi-currency group total.** How should a counterparty total be expressed
   when its unsettled trades span more than one currency — per-currency subtotals,
   a reporting-currency conversion, or both? (See Edge case 7.)
4. **`MATURED` vs settled.** Does a `MATURED` trade always mean its settlement is
   complete (hence excludable), or can a matured trade still be unsettled?
5. **Instrument field.** Which field carries the trade's instrument (reference
   id) for display? Not resolved from the KB.
6. **Day-count for "days overdue".** Calendar days or business days? (Both are
   supported by the system; recommended default: calendar days.)
7. **Tie-break for group ordering** when two counterparties have the same total
   outstanding.
8. **Scope of "live obligation".** Are `PROPOSED`/`NEW`/`PENDING` (not-yet-
   validated) trades in scope for chasing?
9. **Internal/no-counterparty trades** — exclude, or show in a dedicated group?
10. **Report granularity for multi-leg trades** — one row per trade or per leg?

## 9. Knowledge sources

Findings in this document come from the project knowledge base (Openlink
Findur / Endur online help, v25), cited by the document they came from:

- **Trade table & fields** — `Trading Manager → Transaction Listing` (`settle_date`,
  `position`, `currency`, counterparty contact); `OpenJVS Programmers Guide`
  (reads from `ab_tran`); `Database Purging` (lists `ab_tran` and related tables).
- **Identifiers & amendment/buyout model** — `Trading Manager → Transaction Status`
  ("original trade retained … a brand new trade with a new transaction number (but
  same deal number) is created"); `TPM Step Guide → Get Tran Info` (`tran_num`);
  `Liquid and Bulk APM Package User Guide` (`deal_num` "constant for the life of the
  deal").
- **Counterparty** — `Trading Toolsets → Provisional` / `Swaption`
  (`ab_tran.external_bunit`, "the business unit of the counterparty"); `BTC Gateway
  User Guide` (`Ext Bunit`); `Settlements Payment Entry` (`ext_bunit`, "the name of
  the counter party's business unit").
- **Currency** — `Trading Manager → Transaction Listing` (`currency` id_number);
  `Administration Manager → Currencies` (`currency.id_number`, `currency.name`,
  three-letter code).
- **Trade-status vocabulary** — `TOF Gateway User Guide` (string values
  `NEW`/`VALIDATED`/`PENDING`/`PROPOSED`); `ICE ECMS and TradeVault Installation
  Guide` (full status map); `DTCC/SDR Gateway User Guide` (`Cancelled`, `Buyout`,
  `Partial Buyout`, `Partial Novation` "within Findur").
- **Settlement is a separate lifecycle** — `Dashboard Standard Content v1.1`
  (settlement event/document statuses); `Reference Explorer → Settlement
  Instruction Editor` (`settlement_instruction.settle_status`: `Authorized`,
  `Pending`, `Do Not Use`, `Template`) — a *different* object from the trade.
- **Partial settlement / amounts** — `Settlements Payment Entry` ("Suggested Amount
  To Be Applied = Settle Amount minus Previously Applied Amount"); `Cash Position
  Desktop User Guide`.
- **Business date & on-demand default** — `HedgePak Standard Reports` (default
  report date; `AS_OF_REFERENCE` `today`/`yesterday`/`lgbd`); `Trading Toolsets →
  DIGOPT` (Business Date via System Date Roll).
- **Day-count conventions** — `DTCC/SDR Gateway User Guide` (calendar vs business
  days).

Items **not** found in the KB and therefore held as Open Questions (§8): the
trade-level settlement-status field/value ("unsettled"), the exact outstanding
amount field, the instrument field, the multi-currency total convention,
`MATURED` semantics, the day-count convention, the tie-break, the
not-yet-validated scope, the no-counterparty handling, and multi-leg granularity.
