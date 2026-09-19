# Architecture — Report of trades past their settlement date that are still unsettled

Ticket: **#6**

This is the design for the functional specification
[`docs/functional/issue-6.md`](../functional/issue-6.md) (the spec, not this
document, is the authority on behaviour, rules and edge cases). It states the
shape of the solution, the data and how it moves, the decisions made and why,
and what is left open for confirmation.

## 1. Shape of the solution

The system of record is Openlink Findur / Endur ETRM. The platform for this
work is **JVS**, whose in-memory `Table` provides the selecting, sorting,
grouping and totalling the report needs, and whose reporting facilities
present the result. (Source: `OpenJVS Programmers Guide` §6.3–§6.5 — a JVS
script builds a `Table`, formats it — "Options include the ability to sort,
group, or sum data" — and hands it to the `Report` API or saves it to Excel;
`Report Builder User Guide` — aggregations `Avg, Count, Max, Min, Sum` and
`Sub Total` are standard report-layer features.)

**The deliverable is one JVS report script** — a single class implementing
`IScript.execute(IContainerContext)`, the script pattern documented in the KB
(`OpenJVS Programmers Guide` §6.3.2, §6.5). It is run **on demand**: Findur
launches a JVS script with an argument table through the AVS facilities
(`Accounting Manager` — "AVS → Run Script … the columns (i.e., fields) that
will be passed as arguments"; `Run Task`), or from the command line
(`plugin_util.bat`, `OpenJVS Programmers Guide` §7). The business date is one
of those argument fields.

No component beyond the script is created:

- **No new tables, no write-back.** The script issues selects only; it never
  inserts, updates or deletes (spec §7; ticket scope).
- **No helper classes.** The work is a sequence of platform calls
  (`DBase` → `Table` → `Report`); Java's role is to wire those calls together,
  in the one script body. Where the platform's `Table` provides selecting,
  sorting, grouping and totalling, the script does not re-implement any of
  that.
- **No scheduled run.** On demand only (spec §7).

The script's name, package and report file name are implementation details
left to the developer; the steps below are the contract.

## 2. Data

Tables and columns the design names, with where the knowledge came from:

| Table | Used for | Columns named | Source |
|---|---|---|---|
| `ab_tran` | the trades (report rows) | `tran_num` (row key), `deal_num` (trace), `tran_status`, `settle_date`, `position`, `currency` (id), `external_bunit` (id), `ins_num` | `Trading Manager → Transaction Listing` (`settle_date`, `position`, `currency`); `TPM Step Guide → Get Tran Info` (`tran_num`); `Liquid and Bulk APM Package User Guide` (`deal_num`); `Trading Toolsets → Provisional`/`Swaption` (`external_bunit`); `OpenJVS Programmers Guide` §6.4 (SQL over `ab_tran.tran_num`, `ab_tran.ins_num`, `ab_tran.position`) |
| `bunit` | counterparty name | business-unit key (`bunit.id`, keyed as `bunit_id` in `Transaction Party User Guide`), `bunit_name` | `Trading Toolsets → BOND Authorization` (`ab_tran_party.bunit.id`); `STD EOD Position Processing` ("[bunit_name] is the name of the business unit") |
| `currency` | currency code | `id_number`, `name` (the three-letter code) | `Administration Manager → Currencies` (`currency.id_number`; "the currency's three-letter code … `currency.name`") |
| `instruments` | instrument *type* (display only, see D5) | `id_number`, `name`, `longname` | `Administration Manager → Auto Document Release` ("Available Instruments … `instruments.id_number`"); `Administration Manager → Tran Info User Defined Ins` (`instruments.name`, `instruments.longname`) |
| `configuration` | the run's default business date | `business_date` (the Current Business Date / System Date) | `Operations Manager → System Date Roll` ("Current Business Date … Database Table.Column: `configuration.business_date`") |
| `holiday_list` / `holiday_detail` | only if the business-day convention is chosen (D6) | holiday schedules and details | `Holiday Schedule Import/Export` ("holiday schedules are stored in the `holiday_list` database table … `holiday_detail`") |

Dates in the system are **Julian integers** — the standard column type is
"Integer, JulianDate" (`Trading Toolsets` field tables), and the date-roll
function takes "the Julian date to which the system's Business Date is to be
rolled" (`Market Manager → Grid Point Formulas`, `roll_to_dates`).
Consequence used in S1 and S3: comparing and differencing dates is plain
integer arithmetic.

The `tran_status` vocabulary is the system's own status map, with numeric
ids (Source: `ICE ECMS and TradeVault Installation Guide` §10.8.2, "Transactionally
Related Deal and Event Statuses"; the `OpenJVS Programmers Guide`'s own
example tests `tran_status = 3`, which is `Validated` in the same map — the
status column is numeric, and its meaning comes from this map):

| id | status | id | status | id | status |
|---|---|---|---|---|---|
| 1 | Pending | 14 | Deleted | 29 | Rollover New |
| 2 | New | 15 | Template | 30 | Buyout Split New |
| 3 | Validated | 17 | Cancelled New | 31 | Novation New |
| 4 | Matured | 18 | Buyout New | 32 | View Buyout |
| 5 | Cancelled | 19 | Amended New | 33 | View Novation |
| 6 | Buyout | 22 | Closeout | 34 | Extend New |
| 7 | Proposed | 23 | Split Closed | 35 | Exercise Cancel New |
| 10 | Amended | 24 | Giveup Closed | | |
| | | 25 | Amended Closed | | |
| 26 | Assignment New | 27 | Recoupon New | 28 | Euro Redenominated New | |

## 3. Steps of the work

The steps are the components of the design; each names the platform call it
rests on and the data in/out.

### S0 — Resolve the business date

In: the script's argument table (the AVS argument fields). Out: one Julian
integer `business_date`.

- If the caller supplied a business date, use it.
- Otherwise default to the system's current business date, read from
  `configuration.business_date` (S0's only database read; Source:
  `Operations Manager → System Date Roll`).

This realises the spec's "run on demand for a chosen business date, defaulting
to today" (§4): in this system, "today" is the Business Date maintained by the
System Date Roll, not the wall clock.

### S1 — Select the reportable trades

In: `business_date`. Out: one `Table`, one row per reportable trade, carrying
the report columns and the display names.

One SQL select, loaded into a new `Table` by the documented pair
`DBase.runSql(…) → DBase.createTableOfQueryResults(…)` (`OpenJVS Programmers
Guide` §6.3.2). The select:

- takes the report columns from `ab_tran`: `tran_num`, `deal_num`,
  `settle_date`, `position`, `currency`, `external_bunit`, `ins_num`;
- joins `bunit` on the business-unit key for the counterparty name
  (`bunit_name`), and `currency` on its `id_number` for the code
  (`currency.name`) — the name joins, so the output table already holds the
  display values; the id columns can then be hidden (`colHide`, §6.4);
- **rule 1 — settlement date is past:** `settle_date` strictly less than
  `business_date` (spec §3 rule 1; edge case 11: a trade settling *on* the
  business date is not yet overdue and drops out here);
- **rule 2 — still a live obligation:** `tran_status` in the live set
  defined by decision D8 (excluded: not-yet-validated originals
  `Pending`/`New`/`Proposed`, and the terminal/void family `Cancelled`,
  `Buyout`, `Amended` (superseded original), `Deleted`, `Template`, and the
  other closed states in the status map — decisions D8/D9);
- **rule 3 — still owes:** `position` is not zero (spec §3 rule 3, anchored
  on the remaining portion; decision D2);
- **no-counterparty handling:** trades with an empty `external_bunit` are
  *selected*, and the counterparty name falls back to an explicit
  "no counterparty / internal" label so they group as such (decision D9);
- computes the **days-overdue** column as the integer difference
  `business_date − settle_date` (decision D6) — a straight Julian
  difference, which is why it can be computed in the select.

The select is read-only; there is no other database interaction in the script.

### S2 — Present the columns

Platform formatting of the selected table (`OpenJVS Programmers Guide` §6.4):
column titles for the report headers (`setColTitle`), hiding the internal id
columns that were needed only for the joins (`colHide`), and where a shared
reference table is exposed, formatting id columns against it
(`setColFormatAsRef` — the KB documents this for `tran_status`, `toolset`,
`portfolio`, `proj_index`, `currency`; a business-unit shared table is not
documented in the KB, so the S1 name join is the primary mechanism). The
output columns are exactly the spec's §5 per-trade list: trade number, deal
number, counterparty, instrument, settlement date, days overdue, outstanding
amount, currency.

### S3 — Group, count, total

In: the formatted trade table. Out: the same table restructured into
counterparty groups with per-group subtotals.

The platform's grouping/summing is the mechanism, as the KB shows it being
used: `groupFormatted(…)` / `group(…)` for the group structure and
`groupSum(…)` / `sum()` for subtotals and the grand total (`OpenJVS
Programmers Guide` §6.4 and the simulation-results example; `Report Builder
User Guide` — `Count` is a standard subtotal: "Displays the total number of
items in this column, for the group"). The design's requirement on the
result:

- groups keyed by **counterparty** (the "no counterparty" label for internal
  trades, D9);
- within each group, the outstanding **totals are per currency** (decision
  D3) — a subtotal sum of `position` for each currency present in the group;
- each group carries a **count** of its unsettled trades (the standard
  `Count` subtotal over the group's rows);
- an overall grand total row is produced by the platform's `sum()` and is
  optional for the desk but costs nothing to keep.

### S4 — Order the groups

Requirement (spec §5): groups ordered by total outstanding, **largest first**,
with the tie-break of D7.

The ordering is applied as a deterministic step over the group keys — a small
in-memory table (one row per counterparty, its total and count) sorted by
that group's ordering value descending. This is kept in the script rather than
delegated to an unverified sort direction because the platform's documented
summing mechanism is tied to ascending sort — in Table/Blotter, "you cannot
sort in descending order (i.e., 1-D) and sum rows … only works when a column
is sorted in ascending (i.e., 1) order" (`OLF → Configure View`). Sorting the
group-key table in the script is wiring work the platform does not do for us;
the per-group subtotals themselves remain the platform's.

If the platform's `Table` sort proves to support descending order on the
grouping column at implementation time, the same rule applies through that
facility instead — the requirement (D7's order) is what is fixed, not the
mechanism.

### S5 — Emit the report

Platform output facilities, all shown in the KB (`OpenJVS Programmers Guide`
§6.5):

- `Report.reportStart(file, title)` → `Report.printTableToReport(table, …)`
  → `Report.reportEnd()` for the desk's report file;
- `excelSave(…)` for a spreadsheet copy — the desk's chasing medium;
- `viewTable()` for the in-session display.

**Empty result is a valid result** (spec edge case 10): with no reportable
trade the script emits a titled, zero-row report — not an error.

## 4. Decisions and why

Each decision answers an open question in the functional spec (OQ n) or a
question raised in the ticket thread, and records the alternative that was
rejected or deferred.

**D1 — "Unsettled" is anchored on the remaining amount, not on a settlement flag.**
(OQ 1.) The KB surfaces the trade lifecycle (`tran_status`) and the
*settlement-instruction* status (`settlement_instruction.settle_status`) as a
different object, but no single trade-level "settled/not settled" field.
Decided: a trade is unsettled iff it is a live obligation (D8) and still
carries a non-zero remaining portion (`position <> 0`). Nothing is invented to
fill the gap. If the deployment does carry a trade-level settlement-status
field, confirming it would only *tighten* rule 3 (an additional
not-fully-settled test) — flagged as an open item, not assumed.

**D2 — The outstanding amount is `position`.** (OQ 2.) The KB defines
`position` as "the remaining portion of the deal after a specific event has
been executed" (`Trading Manager → Transaction Listing`) — i.e. it is already
the post-settlement remainder, and a fully settled trade carries zero, which
is exactly rule 3's drop-out condition. No separate settled-amount field is
documented on the trade in the KB; the settlement module's own applied-amount
bookkeeping (e.g. `Settlements Payment Entry`, "Suggested Amount To Be
Applied = Settle Amount minus Previously Applied Amount") is that module's
record, not a column on `ab_tran`.

**D3 — Group totals are per currency; no FX conversion by default.** (OQ 3.)
A counterparty may owe in several currencies, and a single cross-currency
total would require a rate source and a rate date that the KB does not
document for this purpose (the KB's FX material is the index-rate
infrastructure, `idx_historical_fx_rates`, not a documented static
currency-rate table). Decided: the group total is the **set of per-currency
subtotals** — count plus outstanding sum per currency — which is honest,
chaseable (the desk chases per-currency payments) and native to the platform
(group by counterparty × currency, sum `position`). A reporting-currency
conversion is a documented extension, not a default: adopting it would
require the desk to choose the reporting currency, the rate source and the
rate date. *Note:* totals sum `position` as stored (signed, direction per the
deal's buy/sell); if the desk wants direction-neutral (absolute) totals that
is a one-expression change — flagged for confirmation.

**D4 — `MATURED` is a live status; settlement is judged by rule 3, not by status.**
(OQ 4.) The KB does not state what `MATURED` implies about settlement.
Decided: `MATURED` is not excluded by status; a matured trade that still owes
(`position <> 0`) remains in the report, and a matured-and-settled one drops
out through rule 3 on its own. This is exactly the spec's anchor (edge case 5)
and needs no knowledge the KB lacks.

**D5 — The instrument column is the trade's instrument number; name resolution is an open confirmation.** (OQ 5.)
The KB documents `ab_tran.ins_num` (used in its own SQL) and the instrument
*type* table `instruments` (`id_number`, `name`, `longname`), but it does not
document the join from a trade's `ins_num` to an instrument name, and the
per-trade instrument-instance table is not in the KB at all. Decided: report
`ins_num` — always present, never ambiguous — and resolve the display name
through `instruments` **only after** the join is confirmed against the
deployment's schema at build time. Displaying a guessed name would be
inventing a fact.

**D6 — Days overdue is in calendar days: `business_date − settle_date`.** (OQ 6.)
Both dates are Julian integers, so the figure is a plain integer difference,
computable in S1 with no date arithmetic. Calendar days match the spec's
recommended default for an aging report. The business-day alternative
(business days only, `DTCC/SDR Gateway User Guide`) would need the
holiday-schedule tables (`holiday_list`/`holiday_detail`) and a choice of
which schedule applies to each counterparty — a real design addition, kept
as the documented alternative rather than the default.

**D7 — Group ordering and tie-break.** (OQ 7.) Groups ordered by their
total outstanding, largest first. Under D3's per-currency basis, a group's
ordering value is its **largest single-currency subtotal** (the currency the
desk would chase first for the most money); ties broken by **higher count
first**, then **counterparty name ascending**. If the desk later adopts a
reporting currency (D3 extension), the ordering value becomes the converted
total and this rule simplifies — the rule is stated so it degrades cleanly.

**D8 — Only live obligations are in scope: validated-and-beyond originals, plus replacement trades; not-yet-validated originals and the terminal/void family are excluded.** (OQ 8.)
Per the spec's safe reading and its edge case 2, the report must show the
*current* transaction of an amended chain, and must not chase deals that were
never accepted. Concretely, over the status map of §2:

- **included:** `Validated` (3), `Matured` (4) (both subject to rule 3), and
  the live replacement states `Amended New` (19) and the other `*New`
  replacement states (`Assignment New` 26, `Recoupon New` 27, `Euro
  Redenominated New` 28, `Rollover New` 29, `Buyout Split New` 30, `Novation
  New` 31, `Extend New` 34) — these are the current obligation of a deal that
  was amended/rolled/etc., and the spec says the current transaction is what
  is reported;
- **excluded:** not-yet-validated originals `Proposed` (7), `Pending` (1),
  `New` (2); and the terminal/void family `Cancelled` (5), `Buyout` (6),
  `Amended` (10, the superseded original), `Deleted` (14), `Template` (15),
  `Cancelled New` (17), `Buyout New` (18), `Closeout` (22), `Split Closed`
  (23), `Giveup Closed` (24), `Amended Closed` (25), `View Buyout` (32),
  `View Novation` (33), `Exercise Cancel New` (35);
- statuses not on the map: **default to included** if they are neither
  clearly terminal nor clearly unvalidated — a chasing report should surface
  an unknown state rather than silently drop an obligation. The developer
  should confirm this default against the deployment's actual status values.

**D9 — No-counterparty trades are shown, in an explicit group.** (OQ 9.)
Excluding internal/no-counterparty trades would let a real obligation vanish
from the chase list without a trace. Decided: select them, label the group
"no counterparty / internal", and let it order with the rest. The group's
name is the only special-casing; nothing else in the pipeline treats them
differently.

**D10 — One row per trade; multi-leg deals are reported at trade level.** (OQ 10.)
The spec keys report rows by `tran_num`, and the ticket asks for the trade's
amount and currency — both of which are trade-level fields on `ab_tran`.
Decided: the report's unit is the `ab_tran` row; a multi-leg deal whose legs
are recorded as separate legs of one trade contributes one row at the trade
level, using the trade's `settle_date`, `position`, `currency`. Leg-level
breakdown is out of scope for this ticket (the ticket asks for the trade's
outstanding, and the KB does not document a leg table the report could
aggregate over).

**D11 — An amendment that moves the settlement date past the business date removes the deal from the report, and it re-enters once the new date passes.** (Ticket comment thread, raised by @l0032.)
Per the spec's amendment model (edge case 2), the original goes to `Amended`
(excluded by D8) and the replacement carries its **own** `settle_date`. If
that date is not yet past the business date, the replacement fails rule 1 —
so neither row is reported. This is adopted as the behaviour, and it is
internally consistent: "past *their* settlement date" means the date in force
on the current obligation, and "days overdue" (business date minus
`settle_date`) would be negative against a future date. **This is the one
behaviour the desk should confirm** — the defensible alternative (keep
renegotiated-but-owing deals visible because they were originally overdue)
cuts against the report's purpose and breaks the days-overdue arithmetic; if
the desk wants it, D7's ordering value and S1's rule 1 both need to change,
and "days overdue" needs a defined display against a future date.

**D12 — Read-only, on demand, one argument.** The script performs no DML of
any kind (spec §7); the business date arrives as an AVS argument field with
the `configuration.business_date` default (S0); no other inputs are needed —
the report's population is fully determined by the system's own data and the
rules above.

## 5. Edge cases, as they fall out of the design

| Spec edge case | Where it is handled |
|---|---|
| 1. Cancelled after settlement date | S1 rule 2 — `Cancelled` (and `Cancelled New`) are in the void family (D8) |
| 2. Amended after settlement date | S1 rule 2 + D8: the superseded original (`Amended`) is excluded; the replacement is reported on its own `settle_date`/`position` — including D11's case where it drops off entirely |
| 3. Bought out / terminated after settlement date | S1 rule 2 — `Buyout`/`Buyout New` in the void family (D8); a partial buyout leaves `position <> 0` and remains reportable (rule 3) |
| 4. Partially settled | rule 3 keeps it, with the remaining `position` as the outstanding (D2) |
| 5. Matured | D4 — live status; rule 3 decides |
| 6. Not-yet-validated | D8 — excluded |
| 7. Multi-currency group | D3 — per-currency subtotals; D7 — ordering value |
| 8. No counterparty | D9 — explicit "no counterparty" group |
| 9. Multi-leg | D10 — one row per trade at trade level |
| 10. Empty result | S5 — a valid zero-row report, not an error |
| 11. Overdue by zero | S1 rule 1 — strictly before, so a same-day settle drops out |

## 6. Out of scope

Per the spec §7 and the ticket: no chasing workflow, no notifications, no
write-back of any kind, no change to how settlement status is recorded, no
scheduled runs. This design adds none of these, and its only database
interaction is the read-only select of S1 plus the default-date read of S0.

## 7. Open items for confirmation

These are carried over from the functional spec's open questions, with the
decision taken (above) and what confirmation would change:

1. **D11 — amended trade drops off when its settlement date is moved past the
   business date.** The desk should confirm this is the behaviour they want on
   the chase list (the ticket's own comment thread asks exactly this).
2. **D3 — per-currency group totals vs a reporting currency.** Decided
   per-currency; adopting a reporting currency requires the desk to name the
   reporting currency, the rate source and the rate date (the KB does not
   document a static currency-rate table for this purpose).
3. **D5 — instrument display name.** `ins_num` is reported; resolving a name
   requires confirming the join to `instruments` (or the deployment's
   instrument-instance table) against the live schema at build time.
4. **D1 — a trade-level settlement-status field, if the deployment has one.**
   Would only tighten rule 3; confirm against the `ab_tran` schema.
5. **D8 — default-inclusion of unknown statuses.** Confirm the deployment's
   status vocabulary against the §2 map before build.

## 8. Knowledge sources

Findings in this document come from the project knowledge base (Openlink
Findur / Endur online help, v25), cited by the document they came from:

- **Script pattern & platform calls** — `OpenJVS Programmers Guide` §6.3.2
  (single-table query code: `Table.tableNew`, `DBase.runSql`,
  `DBase.createTableOfQueryResults`), §6.4 (Formatted Table Plugins: "sort,
  group, or sum data"; `group`, `setColFormatAsRef`, `colHide`,
  `setColTitle`), §6.5 (Reporting: `Report.reportStart`,
  `Report.printTableToReport`, `Report.reportEnd`, `excelSave`;
  `groupFormatted`, `groupSum`, `sum`, `select(…, "SUM, …", …)`), §7
  (`plugin_util.bat`).
- **On-demand launch with arguments** — `Accounting Manager` ("AVS → Run
  Script … the columns (i.e., fields) that will be passed as arguments").
- **Group count as a standard subtotal** — `Report Builder User Guide`
  ("Aggregation … Avg, Count, Max, Min, and Sum"; "Sub Total … Count:
  Displays the total number of items in this column, for the group").
- **Ascending-sort/summing constraint** — `OLF → Configure View` ("In a
  Table/Blotter you cannot sort in descending order (i.e., 1-D) and sum rows").
- **Trades & fields** — `Trading Manager → Transaction Listing` (`settle_date`,
  `position`, `currency`); `TPM Step Guide → Get Tran Info` (`tran_num`);
  `Liquid and Bulk APM Package User Guide` (`deal_num`); `Trading Toolsets →
  Provisional`/`Swaption` (`external_bunit`); `OpenJVS Programmers Guide` §6.4
  (SQL over `ab_tran`).
- **Status vocabulary** — `ICE ECMS and TradeVault Installation Guide` §10.8.2
  (the numeric status map in §2).
- **Business date** — `Operations Manager → System Date Roll`
  (`configuration.business_date`, the Current Business Date);
  `HedgePak Standard Reports` (the on-demand date-default convention).
- **Dates as Julian integers** — `Market Manager → Grid Point Formulas`
  (`roll_to_dates`, "the Julian date …"); `Trading Toolsets` field tables
  ("Integer, JulianDate").
- **Counterparty name** — `Trading Toolsets → BOND Authorization`
  (`ab_tran_party.bunit.id`); `Transaction Party User Guide` (`bunit_id`);
  `STD EOD Position Processing` (`[bunit_name]`).
- **Currency** — `Administration Manager → Currencies` (`currency.id_number`,
  `currency.name`, three-letter code).
- **Instruments** — `Administration Manager → Auto Document Release` and
  `Tran Info User Defined Ins` (`instruments.id_number`, `instruments.name`,
  `instruments.longname`).
- **Holiday schedules (alternative D6 only)** — `Holiday Schedule
  Import/Export` (`holiday_list`, `holiday_detail`).
