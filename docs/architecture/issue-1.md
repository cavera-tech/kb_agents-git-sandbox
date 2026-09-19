# Architecture document — Issue #1: Unsettled trades past their settlement date

**Status:** design for implementation
**Target system:** Openlink Endur/Findur ETRM (trade and settlement platform)
**Specification:** `docs/functional/issue-1.md` (behaviour, rules, edge cases — this document designs against it and does not restate it)
**Prepared:** 2026-09-19

## 1. Summary

The report is a read-only, on-demand selection-and-presentation of live
(Validated) trades whose settlement date is strictly before a chosen business
date, grouped by counterparty with per-group counts and per-currency totals,
ordered by total outstanding, largest first.

It is implemented as a **report application running inside a live
Endur/Findur session**, using the platform's own in-process report machinery
(the `Table` row/column structure and the `Report` text-report output class,
both `com.olf.openjvs`). No new system, no data pipeline, no database writes.

Four components, one data flow:

```
 business date ──▶ C1 ──▶ C2 ─────────────────▶ C3 ─────────────────▶ C4
                   │    │                        │                     │
 parameter:   on-demand  single read-only      in-memory:            grouped layout
 defaulting   run        SQL over ab_tran,     group / per-currency  → report
 + validation      party, currency     totals / ordering /   artifact
                                       days overdue        (file, or
                                                            desktop
                                                            registration)
```

## 2. Placement and host

**Decision: in-session report application (OpenJVS), not an external batch
job or a separate database client.**

- The ticket asks for an on-demand run for a chosen business date, defaulting
  to today. The system's own operational reports are in-session — the
  end-of-day *Missing Settlement Instructions* report is an in-system report
  over validated transactions (KB: Process Documents → *End of Day
  Processing*, §4.6, cited in the functional document).
- The knowledge base documents OpenJVS as the mechanism for scripts and
  reports running inside a live session (KB: *OpenJVS Programmers Guide*),
  with `Table` as its row/column work structure ("used all over in OpenJVS")
  and `Report` as its text-report facility (`reportStart` →
  `printTableToReport` → `reportEnd`; KB code graph, package
  `com.olf.openjvs`).
- No new infrastructure: no ETL, no storage, no credentials beyond the
  session's own. Connecting to a running session is a documented mechanism
  (KB: *Deployment Wizard Command Line Interface*, `-olfsession
  <id_number>`).
- Read-only is structural: the application's only data access is one SELECT;
  the session enforces normal read access.

**Boundary:** a running Endur/Findur session is the precondition. The report
adds no state to it.

## 3. Components and boundaries

### C1 — Parameter layer

- **Input:** `business_date`, one parameter; a business date in the
  system's Julian-date idiom (integer; the system's own guides date their
  SQL in "JULIAN DATE FORMAT", and the report-manager API addresses dates as
  integer julians).
- **Duties:** default to the session's current date when absent; validate
  (parseable business date); hand one date value downstream.
- **Boundary:** no data access.

### C2 — Selection

- **One read-only SELECT** over `ab_tran` joined to `party` and `currency`,
  applying the predicate in §6.1, projecting the trade-row schema in §6.2.
- **Duties:** status filter (Validated + live-deal flag + current version),
  settlement-date filter (strictly before business date, present),
  projection.
- **Boundary:** the only component that touches trade data; the only
  component that issues a statement against the schema. Status id 3
  (Validated) is referenced as a named constant with an explanatory comment,
  following the system's own SQL idiom (KB: *Database Purging* —
  `tran_status = 14 /* TRAN_STATUS_DELETED */`, `trade_flag = 1 /* TRADING.
  This is not an authorization or holding record. */`).

### C3 — Aggregation

- **Input:** the flat trade-row `Table`. **Output:** an ordered list of
  counterparty groups.
- **Duties:** group by counterparty business unit; count trades; sum
  outstanding **within each currency only** (opposite-side positions offset
  arithmetically); derive each group's ordering key (its largest
  per-currency total); order groups by key descending, ties by counterparty
  name; order trades within a group by settlement date ascending, then trade
  identifier; compute days overdue per trade (whole calendar days between
  settlement date and business date).
- **Boundary:** pure in-memory transformation over the `Table`; no further
  data access; no knowledge of the output format.

### C4 — Presentation / output adapter

- **Duties:** lay out the ordered groups as a presentation `Table` — one
  header block per counterparty (name, trade count, one total line per
  currency present) followed by that group's trade rows — and deliver it to a
  sink, with the report title carrying the business date.
- **Default sink:** a text report file via the `Report` class, with one
  section per counterparty — mirroring the system's own per-business-unit
  report files (e.g. `<Bunit name>_missing_settlement_instructions.stt`,
  KB: *End of Day Processing*, §4.6).
- **Alternative sink (open item A2):** a report registered in the desk's
  Reporting Desktop with the business date as a user parameter (the report
  manager API — `RptMgr`, `com.olf.openjvs` — documents the parameter
  mechanism). The application code is identical up to the sink.
- **Boundary:** the only component that writes — and it writes a report
  artifact (a file), never a database record. An empty group list renders as
  an empty report: a valid outcome, not an error (functional document §4.10).

### Interfaces (names and contracts only)

| Component | Interface | Contract |
|---|---|---|
| C1 | `run(business_date?)` | start a report run with an optional business date; defaults to today; returns a completed report or an explicit parameter error |
| C2 | `selectOverdueTrades(business_date) → Table` | zero or more trade rows (§6.2); empty is a normal result |
| C3 | `groupByCounterparty(rows) → Groups` | ordered groups (§6.3); preserves the input row set exactly |
| C4 | `render(groups, business_date) → artifact` | presentation layout → report artifact; empty groups → empty report |

## 4. Data model

Tables and their role in this report (field meanings per the functional
document §3, each cited there to the knowledge base):

| Table | Role | Identity used |
|---|---|---|
| `ab_tran` | fact — one row per trade version | `tran_num`; row identity `tran_num` + `version_number`; selection keeps `current_flag = 1` only |
| `party` | reference — counterparty name | `party_id` |
| `currency` | reference — currency code | `id_number` |
| `trans_status` | status picklist — source of the status id; **not joined** (the predicate is fixed on Validated) | `trans_status_id` |

Join map (logical, by the documented id conventions):

| From | To | Meaning |
|---|---|---|
| `ab_tran.external_bunit` → `party.party_id` | counterparty business unit | party id; display name from `short_name` / `long_name` |
| `ab_tran.currency` → `currency.id_number` | currency | code from `currency.name` ("the currency's three-letter code", KB: *ADMIN Static Data* → Currencies) |
| `ab_tran.tran_status` = 3 | Validated (constant) | id per the system's standard status list (functional document §2, citing *ICE ECMS and TradeVault Installation Guide* §10.8.2) |
| `ab_tran.trade_flag` = 1 | live deal | "not an authorization or holding record" (KB: *Database Purging*) |
| `ab_tran.current_flag` = 1 | current version | "Older versions of a deal have a current_flag value of zero" (KB: *History Tables*) |

**Versioning semantics (drives the amendment rule).** Per KB: *History
Tables*, each amendment adds a new `ab_tran` row with a new `tran_num` under
the same `deal_tracking_num`, `version_number` increments, and exactly one
row per deal carries `current_flag = 1`. Consequences:

- the selection predicate's `current_flag = 1` selects the deal's live
  version; the old version is doubly excluded — by status (Amended) **and**
  by version flag (redundant guard, per functional document §5);
- the current version reports its own, possibly amended, settlement date,
  counterparty, and outstanding amount — no cross-version computation is
  needed.

**Assumption (open item A4):** the joins above are the declared
relationships in the target installation. The knowledge base documents each
reference field as an id ("Displays the id_number of …"), but its graph does
not expose these tables' key/foreign-key declarations (both the keys and
relationships lookups returned nothing for `ab_tran`, `party`, `currency`,
`trans_status`); confirm against the installation's data dictionary.

## 5. Data flow, one run

1. The desk invokes the report, optionally with a business date. C1 applies
   the default (today) and validates.
2. C2 issues the single read-only SELECT (`ab_tran` ⋈ `party` ⋈ `currency`)
   with the §6.1 predicate. The session returns a flat `Table` of trade rows
   — possibly empty.
3. C3 transforms in memory: days overdue per row; groups by counterparty;
   per-currency sums; ordering keys; group and row ordering.
4. C4 lays out the grouped `Table` and delivers the artifact (default: text
   report file, one section per counterparty).
5. The run ends. No write to the schema, no status change, no cached copy.

Data moves one way, once, in-process. A bounded population (overdue live
trades) crosses the session boundary into the report artifact — the only
output of the system.

## 6. Schemas

### 6.1 Selection predicate (the only query)

| # | Condition | Functional rule |
|---|---|---|
| 1 | `tran_status` is Validated (id 3) | §2, §4.2 |
| 2 | `trade_flag` = 1 | §4.2 (system live-deal convention) |
| 3 | `current_flag` = 1 | §4.2, §5 (amendment rule) |
| 4 | `settle_date` strictly before `business_date` | §4.2, §4.4 |
| 5 | `settle_date` present | §5 (missing date → excluded; Q3) |
| 6 | projection per §6.2 | §4.3 |

### 6.2 Trade-row schema

| Field | Source | Note |
|---|---|---|
| trade identifier | `ab_tran.tran_num` | display identity |
| deal reference (optional) | `ab_tran.deal_tracking_num` | deal-level tracking number |
| counterparty | `ab_tran.external_bunit` → `party.short_name` (fallback `long_name`) | group key is `external_bunit` |
| instrument | `ab_tran.ins_num` | per functional document §3 |
| settlement date | `ab_tran.settle_date` | business date |
| days overdue | `business_date − settle_date` | whole calendar days, computed in C3 |
| amount outstanding | `ab_tran.position` | as stored; zero included (Q4 default) |
| unit | `ab_tran.unit` | unit of the amount |
| currency | `ab_tran.currency` → `currency.name` | three-letter code |

### 6.3 Group schema

| Field | Rule |
|---|---|
| counterparty | `external_bunit` + display name |
| trade count | number of the group's rows |
| per-currency totals | one entry per currency present: currency, arithmetic sum of `position` |
| ordering key | largest of the per-currency totals |

Ordering: groups by ordering key descending, ties by counterparty name;
rows within a group by settlement date ascending, then trade identifier
(functional document §4.8).

## 7. Decisions and rationale

- **D1 — Host: in-session OpenJVS report, not an external batch job.**
  The ticket's shape (on-demand, chosen business date, default today) is the
  system's own report idiom; the knowledge base grounds the in-session
  machinery (`Table`, `Report`, session connection); and it costs no new
  infrastructure. (§2)
- **D2 — Selection pushed into SQL, not client-side filtering.** The
  predicate is a plain filter over `ab_tran` facts; doing it in the database
  keeps the client population minimal and matches the system's own precedent
  (SQL over `ab_tran` with `trade_flag = 1` and a commented status id, KB:
  *Database Purging*; live-deal queries in the *OpenJVS Programmers
  Guide*).
- **D3 — Aggregation in memory over the `Table`, not in SQL.** The
  functional rule "totals per currency; group order by the largest
  per-currency total" (§4.7) is a two-stage aggregation that is clearer in
  code than in nested SQL; the input population is bounded (overdue live
  trades); and the business rules become unit-testable over a synthetic
  `Table` with no live session. Accepted trade-off: totals are the
  application's arithmetic over the stored `position` values — exactly the
  functional document's "arithmetic sum" rule — rather than the database
  engine's.
- **D4 — Per-currency totals only; never sum across currencies.** No FX rate
  source exists in the ticket (functional document Q6); the ordering key is
  the largest per-currency total per §4.7.
- **D5 — Days overdue as whole calendar days, computed in C3.** The
  functional default (Q7); isolated in one function so a business-day basis
  would be a single-function swap without touching selection or
  presentation.
- **D6 — Group by counterparty business unit (`external_bunit`), resolve
  names at presentation.** The system's own reporting convention (per
  business unit, KB: *End of Day Processing* §4.6; functional document Q5
  default). Keeps the group key a stable id; the name is display-only, so a
  party rename does not split or merge groups.
- **D7 — Read-only by construction.** The only data access is one SELECT;
  the only write is a report artifact via the `Report` class. No write API
  appears in any component interface (§3), so the ticket's out-of-scope list
  (no chasing, no notifications, no write-back, no status change) is
  enforced structurally, not by discipline.
- **D8 — Status id as a named constant, not a `trans_status` join.** The
  predicate is fixed on Validated by design; the system's own SQL references
  status ids with comments (KB: *Database Purging*). `trans_status` stays
  the documented source of truth for the mapping; if an installation maps
  ids differently, the fix is one constant in C2 (open item A3).
- **D9 — Output as a thin adapter.** Default sink is the text report file —
  the `Report` class is the knowledge base's documented "simple text
  reports" facility. Desk registration (Reporting Desktop / report manager,
  `RptMgr`) is a configuration alternative on the same rendered `Table`, not
  a re-architecture (open item A2).
- **D10 — Empty result is a normal path.** Zero rows at C2 is not an error;
  C3 and C4 produce an empty report (functional document §4.10). No special
  branch, no distinct error state.

## 8. Edge behaviour mapped to components

| Case (functional document §5) | Handled by | Result |
|---|---|---|
| Cancelled after settlement date | C2 — status is not Validated | excluded |
| Amended after settlement date | C2 — old version: status Amended and `current_flag = 0`; current version: its own amended fields | old version excluded; current version included iff its amended settlement date is before the business date |
| Transitional status (Amended New, …) | C2 — status is not Validated (Q1 default) | excluded |
| Settlement date on or after business date | C2 — strict `settle_date < business_date` | excluded |
| Settlement date missing | C2 — predicate 5 | excluded (Q3) |
| Zero outstanding | C3 — no filter on `position` | included (Q4 default) |
| Multi-currency counterparty | C3 — per-currency sums | one total per currency; ordering key = largest |
| Opposite-side positions in one group | C3 — arithmetic sum within currency | they offset, per §4.7 |
| No overdue trades | C4 | empty report, valid outcome |

## 9. Verification strategy

- **Unit (no session required):** C3 over synthetic `Table` fixtures —
  grouping; per-currency sums; offsetting; ordering-key selection; group and
  row ordering with ties; days-overdue computation (including the
  settlement-date-equal-to-business-date boundary, which is excluded); empty
  input.
- **Integration (one sandbox session):** seeded deals covering the §8 rows —
  a Validated overdue deal; an amended pair (old + current); a Cancelled
  overdue deal; a zero-outstanding overdue deal; a multi-currency
  counterparty; a deal settling on the business date; a future-dated deal.
  Assert the selection set, group totals, and ordering.
- **Read-only assertion:** after a run, the touched tables are unchanged —
  row counts (or the session audit trail) identical to before the run.

## 10. Open items

**Carried from the functional document** (each already has a stated default,
reflected in the design above; none blocks it):

- **Q1 — transitional statuses:** excluded (C2, D8).
- **Q2 — event-level settlement (Nostro Flag) scope:** if the desk wants the
  event-level population, C2 gains one additional join to the settlement
  event table; selection is isolated, so C1/C3/C4 are untouched.
- **Q3 — missing settlement dates:** excluded (predicate 5).
- **Q4 — zero-outstanding trades:** included (no `position` filter).
- **Q5 — counterparty granularity:** business unit (D6).
- **Q6 — common-currency totals:** not produced; no FX source named (D4).
- **Q7 — days-overdue basis:** calendar days; single-function swap to
  business days (D5).

**Architecture-specific:**

- **A1 — Party display name.** Default `short_name`, fallback
  `long_name` when absent; both are documented (functional document §3).
  Confirm with the desk which it prefers.
- **A2 — Delivery.** Text report file (default) or Reporting Desktop
  registration; a C4 sink decision. Needs the desk's preference.
- **A3 — Status id mapping.** The knowledge base lists 3 = Validated
  (functional document §2, citing *ICE ECMS and TradeVault Installation
  Guide* §10.8.2). Confirm against the installation; the fix is one constant
  in C2 (D8).
- **A4 — Key/foreign-key declarations.** The knowledge base documents the
  id fields but its graph exposes no key or relationship declarations for
  `ab_tran`, `party`, `currency`, `trans_status`. Confirm the join map of §4
  against the installation's data dictionary.

## 11. Where the architecture's grounding came from

| Fact | Knowledge base source |
|---|---|
| In-session OpenJVS report host; `Table` ("used all over in OpenJVS") and `Report` (`reportStart`, `printTableToReport`, `reportEnd`) as `com.olf.openjvs` classes; `RptMgr` parameter mechanism | code graph: `Table`, `Report`, `RptMgr`; KB: *OpenJVS Programmers Guide* |
| Live-deal SQL idiom: `trade_flag = 1` ("not an authorization or holding record"), commented status ids (`tran_status = 14 /* TRAN_STATUS_DELETED */`) | KB: *Database Purging* (SQL examples on `ab_tran`) |
| Versioning semantics: new row per amendment, same `deal_tracking_num`, `current_flag = 0` on old versions, exactly one current row per deal | KB: *History Tables* |
| Field meanings (`settle_date`, `position`, `currency` = "Displays the id_number of the currency", `external_bunit`, `version_number`, …) | KB: Trading Manager → *Transaction Listing* (cited via functional document §3) |
| Status vocabulary and standard id list (3 = Validated, …) | KB: Trading Manager → *Transaction Statuses*; *ICE ECMS and TradeVault Installation Guide* §10.8.2 (cited via functional document §2) |
| Per-business-unit report precedent (`<Bunit name>_missing_settlement_instructions.stt`) | KB: *End of Day Processing* §4.6 (cited via functional document §4.5) |
| Julian date idiom in system SQL and report APIs | KB: *Database Purging* ("JULIAN DATE FORMAT"); code graph: `RptMgr.getDirForDate(int jd, …)` |
| Session connection mechanism (`-olfsession <id_number>`) | KB: *Deployment Wizard Command Line Interface* |
