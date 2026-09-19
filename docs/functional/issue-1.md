# Functional document — Issue #1: Unsettled trades past their settlement date

**Status:** functional specification for design
**Target system:** Openlink Endur/Findur ETRM (trade and settlement platform), per the knowledge base's Online Help V25 corpus
**Prepared:** 2026-09-19

## 1. What is asked

A read-only, on-demand report for an operations desk that lists **trades whose
settlement date has already passed but which are not yet settled**, so the desk
can chase them.

For each trade the report shows:

- the trade identifier
- the counterparty
- the instrument
- the settlement date
- how many days it is overdue
- the amount outstanding, with its currency

Trades are **grouped by counterparty**, with a **count** and a **total
outstanding** per group. Groups are ordered by total outstanding, **largest
first**.

The report runs **on demand for a chosen business date, defaulting to today**.

## 2. "Unsettled" in the target system's own vocabulary

The knowledge base documents two distinct status layers in Endur/Findur. Both
are stated in the system's own terms; this report operates at the first layer.

**Trade (transaction) lifecycle status** — `ab_tran.tran_status`, a picklist
held in the `trans_status` table (`trans_status_id`, `name`). The system
defines the relevant states as follows (KB: Trading Manager → *Transaction
Statuses*):

| System status | The system's own definition | For this report |
|---|---|---|
| **Validated** | "Status of deals that are live and are still active" | **in scope — this is the unsettled state** |
| **Matured** | "This is the final status for typical deals" | out of scope — settled/closed |
| **Cancelled** | deal "actually entered in error, and can be cancelled" | out of scope — closed |
| **Amended** | "Old versions of deals that went through the amendment process" | out of scope — superseded version |
| **Buyout** | "Status of deals that are bought out" | out of scope — closed |
| **Closeout / Split Closed / GiveUp Closed / Amended Closed** | closed-out deal variants | out of scope — closed |
| **Deleted** | deal has been deleted (only possible before validation) | out of scope |
| **Template** | "Templates are not deals" | out of scope |
| **New / Pending / Proposed** | holding states for deals in progress; the system requires them to become "Validated … or Deleted" before end-of-day processing | out of scope — not live deals |
| **Amended New / Cancelled New / Buyout New / Novation New / …** | "transitional phase toward becoming <final status>" | out of scope by default (see Open questions, Q1) |

The system's own operational reports use the same notion of a live deal: the
end-of-day *Missing Settlement Instructions* report "identifies any **validated
transactions** in the database" (KB: Process Documents → *End of Day
Processing*, §4.6), and the platform's example queries for live deals select
`ab_tran` rows with `tran_status = 3` (Validated) (KB: *OpenJVS Programmers
Guide*; the standard status identifier 3 = Validated is listed in KB: *ICE
ECMS and TradeVault Installation Guide*, §10.8.2).

**Settlement-event status** — at the level of individual settlement events, the
system's vocabulary is the *Nostro Flag*, whose values are **Unsettled** and
**Settled** ("All rows with a Nostro Flag status of Unsettled … Events having
a Settled status are never affected" — KB: *Transaction Party User Guide*).
This layer is context for what "unsettled" means in the system; the ticket's
fields are all trade-level, and the ticket excludes "any change to how
settlement status is recorded", so this report is defined at the **trade
level** (see also Open questions, Q2).

**Definition adopted for this report.** A trade is *unsettled* when it is a
live transaction — transaction status **Validated** — and it has **not** been
processed to a closed, superseded, or draft state (Matured, Cancelled,
Amended, Buyout, Closeout, Split Closed, GiveUp Closed, Amended Closed,
Deleted, Template, New, Pending, Proposed, or a transitional "… New" state).
The report lists such trades only when their settlement date is strictly
before the chosen business date.

The system uses the same business idiom for ageing: "Overdue Transactions are
expected payments or receipts from the past which have not settled", with a
configuration controlling "how many days in the past are included" (KB:
*Cash Position Desktop User Guide*, §2.1.3.2), and refers to trades whose
settlement has not happened as "**unsettled trades**" (KB: Operations Manager
→ *Security Inventory*).

## 3. Data the report uses (named per the ticket)

| Report element | System field | Meaning per the knowledge base |
|---|---|---|
| Trade identifier | `ab_tran.tran_num` | "A unique ID number assigned to the transaction" |
| (Deal-level tracking number, for reference) | `ab_tran.deal_tracking_num` | "A unique tracking number assigned to the deal" |
| Counterparty | `ab_tran.external_bunit` | "The external business unit (or counterparty)" — stores a party ID |
| Counterparty name | `party.short_name` / `party.long_name` | party reference data, keyed on `party.party_id` |
| Instrument | `ab_tran.ins_num` | "A unique ID assigned to each instrument" (type via `ins_type`/`ins_class`) |
| Settlement date | `ab_tran.settle_date` | "The date the deal settles" |
| Unsettled status | `ab_tran.tran_status` → `trans_status` | lifecycle status (see §2) |
| Amount outstanding | `ab_tran.position` | "The remaining portion of the deal after a specific event has been executed" |
| Unit of the amount | `ab_tran.unit` | "The unit or measure used in the deal" |
| Currency of the amount | `ab_tran.currency` → `currency.name` | "The currency the deal was transacted" (stores an id) |
| Current version | `ab_tran.current_flag`, `ab_tran.version_number` | "whether the listing … is the current version of the deal"; version "increments to 1 [sic: 2] whenever deal is amended" |
| Live-deal flag | `ab_tran.trade_flag` | "Indicates whether it is OK to execute the trade. Displays '1' for yes or '0' for no" |

All of the above are cited in the knowledge base (KB: Trading Manager →
*Transaction Listing* field list; Trading Toolsets → *FinFut Transaction Input
Screen*, which cites `Database Table.Column: ab_tran.tran_status` and
`ab_tran.settle_date`; *OpenJVS Programmers Guide* queries on `ab_tran`,
`party`).

## 4. Behaviour

1. **Input.** One parameter: the **business date**. Default: today. Any past
   (or future) date may be supplied; a future date simply yields an empty
   report. All dates in the report are interpreted as business dates.
2. **Selection.** A trade appears in the report iff, as of the run:
   - its transaction status is **Validated** (live — see §2), and
   - its settlement date (`settle_date`) is **strictly before** the business
     date, and
   - it is the **current version** of the deal (see §5, amendment).
   The system's own live-deal queries also require the deal's executable flag
   to be set (`trade_flag = 1`); the report follows that convention.
3. **Trade rows.** Each selected trade is shown with: trade identifier
   (`tran_num`), counterparty (name), instrument, settlement date, days
   overdue, and amount outstanding in its unit and currency.
4. **Days overdue.** Whole calendar days between the settlement date and the
   business date (e.g. settlement date 2026-09-10, business date 2026-09-19
   → 9 days). A trade settling **on** the business date is not overdue and is
   not in the report.
5. **Grouping.** One group per counterparty (group key: the counterparty's
   business unit, `external_bunit`, resolved to its name in the party
   reference data — consistent with the system's own reports, which are
   grouped by business unit, e.g. the end-of-day report file
   `<Bunit name>_missing_settlement_instructions.stt`).
6. **Group summary.** Each group carries: the number of trades, and the total
   outstanding per currency (see rule below).
7. **Totaling rule.** Outstanding amounts are summed **within a single
   currency**; amounts in different currencies are never added together.
   Within one currency, opposite-side positions offset (the total is the
   arithmetic sum of the outstanding amounts recorded for the group's trades).
   A group spanning several currencies shows one total per currency; the
   group's "total outstanding" for ordering purposes is its largest
   per-currency total.
8. **Ordering.** Groups are listed by total outstanding, **largest first**;
   ties are broken by counterparty name. Within a group, trades are listed by
   settlement date ascending (oldest/overdue-longest first), then trade
   identifier.
9. **Read-only.** The report selects and presents only. It never changes a
   trade, a status, or any settlement record.
10. **Empty result.** If no trade satisfies the selection, the report is
    presented as empty (zero groups); this is a valid outcome, not an error.

## 5. Rules for the edge cases the ticket calls out

**Trades cancelled after their settlement date.** Excluded. In the system's
vocabulary a cancellation moves a live (Validated) deal through *Cancelled
New* to **Cancelled** — only Validated deals can be cancelled, and once
cancelled the deal is closed. A closed deal carries no live settlement
obligation to chase, so it never appears in the report, regardless of its
settlement date.

**Trades amended after their settlement date.** The system retains the
superseded version for audit and marks it **Amended**; "a brand new trade with
a new transaction number (but same deal number) is created" and becomes the
live version (KB: *Transaction Statuses*, amendment flow; `version_number`
"increments … whenever deal is amended"). Consequences for the report:

- the **old (Amended) version is excluded** — it is not a live deal and its
  amounts are history, not an outstanding obligation;
- the **current (Validated) version is included** if its — possibly amended —
  settlement date is before the business date, showing its **amended**
  settlement date, counterparty, and outstanding amount;
- a two-step amendment leaves the new version in **Amended New** until
  confirmed; by default such transitional versions are excluded (see Q1).

**Settlement date on or after the business date.** Excluded — the trade is not
yet past its settlement date (it is due or future, not overdue).

**Settlement date missing.** A trade with no settlement date cannot be "past
its settlement date"; it is excluded (see Q3 for a data-quality option).

**Amount outstanding of zero.** Included by default: the trade is still
unsettled and past due, and the group totals will show it for what it is
(see Q4).

## 6. Out of scope (per the ticket)

- Chasing workflow, notifications, or any escalation path.
- Any write-back: status changes, corrections, or updates to trades.
- Any change to how settlement status is recorded in the system.
- Anything beyond selection and presentation of the data named above.

## 7. Open questions

These do not block the behaviour above; each is flagged where the knowledge
base does not settle the point.

- **Q1 — Transitional statuses.** Should deals in an in-flight transition
  (Amended New, Cancelled New, Buyout New, Novation New, …) be included
  while in that state? Default: excluded, since the system's own vocabulary
  reserves "live and active" for **Validated** and the platform's live-deal
  queries select `tran_status = 3` only.
- **Q2 — Event-level settlement.** The report is defined at trade level
  (Validated + past settlement date). The system also tracks settlement per
  settlement event (Nostro Flag: Unsettled/Settled). Trades that the desk has
  already processed to a terminal status (e.g. Matured) but whose settlement
  was never recorded as Settled would **not** appear. Should the desk want
  that population, the selection would need to consult the event-level
  settlement status — confirm the intended scope.
- **Q3 — Missing settlement dates.** Should trades with a missing settlement
  date be listed in a separate data-quality section instead of silently
  excluded? Default: excluded from the main listing.
- **Q4 — Zero-outstanding trades.** Include (default, completeness of the
  unsettled population) or suppress (nothing to chase)?
- **Q5 — Counterparty granularity.** Group by the counterparty's business
  unit (`external_bunit`, the system's own reporting convention) or by its
  legal entity (`external_lentity`)? Default: business unit.
- **Q6 — Common-currency totals.** For counterparty groups spanning several
  currencies, should a single reporting-currency total be produced? That
  requires an FX rate source the ticket does not name; default is
  per-currency totals with the largest one used for ordering.
- **Q7 — Days-overdue basis.** Calendar days (default) or business days per
  the instrument's holiday schedule?

## 8. Where the knowledge came from

Knowledge base: Openlink Endur/Findur **Online Help V25** corpus
(`onlinehelp_v25` collection) and the `db_schema` collection of the `kb`
knowledge base. Specific grounding:

| Fact | Source in the knowledge base |
|---|---|
| `ab_tran` trade table and field meanings (`tran_num`, `external_bunit`, `external_lentity`, `ins_num`, `settle_date`, `position`, `currency`, `unit`, `version_number`, `current_flag`, `trade_flag`, …) | Trading Manager → *Transaction Listing* (field list); Trading Toolsets → *FinFut Transaction Input Screen* (cites `Database Table.Column: ab_tran.tran_status`, `ab_tran.settle_date`, `ab_tran.trade_time`); `db_schema` collection |
| Status vocabulary and definitions (Validated = live/active; Matured = final; Amended/Cancelled/Buyout/Closeout/Deleted/Template meanings; amendment and cancellation flows) | Trading Manager → *Transaction Statuses* ("a complete list of the deal statuses … available through the *trans_status* table") |
| Standard status identifiers (1 Pending, 2 New, 3 Validated, 4 Matured, 5 Cancelled, 6 Buyout, 7 Proposed, 10 Amended, 14 Deleted, 15 Template, 17 Cancelled New, 18 Buyout New, 19 Amended New, 22 Closeout, 23 Split Closed, 24 Giveup Closed, 25 Amended Closed) | *ICE ECMS and TradeVault Installation Guide*, §10.8.2; *TOF Gateway User Guide*, §4.5 (valid values NEW/VALIDATED/PENDING/PROPOSED) |
| Live-deal selection precedent (`tran_status = 3`, `trade_flag = 1`) | *OpenJVS Programmers Guide* (SQL examples on `ab_tran`) |
| Party reference data (`party.party_id`, `short_name`, `long_name`, `party_status`) | *OpenJVS Programmers Guide* (party picklist query); *Transaction Listing* ("Displays the party_id number") |
| Currency reference data (`currency.id_number`, `currency.name`) | *Cash Position Desktop User Guide*, *Auto Match User Guide*, *ADMIN Static Data* (citations of `currency.name`) |
| Event-level settlement vocabulary (Nostro Flag: Unsettled/Settled) | *Transaction Party User Guide* (Process Documents) |
| "Overdue = … in the past which have not settled" idiom | *Cash Position Desktop User Guide*, §2.1.3.2 *Overdue Transactions* |
| "Unsettled trades" usage | Operations Manager → *Security Inventory* |
| End-of-day reporting on validated transactions | *End of Day Processing* guide, §4.6 *Missing Settlement Instructions Report* |
