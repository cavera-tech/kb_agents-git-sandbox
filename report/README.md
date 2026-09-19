# Unsettled trades past their settlement date — report (issue #1)

Implementation of the two documents on this branch:

- `docs/functional/issue-1.md` — behaviour, rules, edge cases
- `docs/architecture/issue-1.md` — components C1–C4, data model, decisions

A read-only, on-demand report for the operations desk: live (Validated)
trades whose settlement date is strictly before the chosen business date,
grouped by counterparty with a trade count and per-currency total
outstanding, groups ordered by total outstanding, largest first.

## Layout

| File | Architecture component |
|---|---|
| `src/com/customer/reports/OverdueUnsettledTradesReport.java` | the OpenJVS `IScript` — C1 parameter, C2 selection, C3 adapter, C4 presentation/sink |
| `src/com/customer/reports/TradeRow.java` | C2→C3→C4 value object (one selected trade) |
| `src/com/customer/reports/CurrencyTotal.java` | C3 value object (one per-currency total) |
| `src/com/customer/reports/Group.java` | C3 value object (one counterparty group) |
| `src/com/customer/reports/Grouping.java` | **C3 pure core** — grouping, per-currency sums, ordering, days overdue; no `com.olf` types |
| `test/GroupingTest.java` | C3 unit tests (architecture §9), runnable with any JDK |

## Running it (in an Endur/Findur session)

Register `OverdueUnsettledTradesReport` as a script (generic category) and
run it on demand. Optionally supply `business_date` via the plugin argument
table — a `business_date` column (Julian integer or `YYYYMMDD`), or
`parameter_name`/`parameter_value` rows; with no argument the session's
business date is used (default: today).

Output: a text report file `unsettled_overdue_trades.txt` in the session's
report directory, titled with the business date. Layout per counterparty, in
ordered blocks:

```
GROUP   line   counterparty, trade count, total outstanding (largest per-currency total)
TOTAL   line   one per currency present
TRADE   lines  trade no, deal no, instrument, settle date, days overdue, amount, unit, currency
```

Every row carries the counterparty name, so the file is self-describing.

## Verification status

- **C3 core: compiled and unit-tested.** `GroupingTest` covers the
  architecture §9 unit list (grouping, per-currency sums, offsetting,
  ordering-key selection, group and row ordering with ties, days overdue,
  empty input) — all assertions pass, run with the Eclipse JDT compiler on
  Java 8 in this session. Reproduce with any JDK:

  ```sh
  cd report
  javac -d out src/com/customer/reports/TradeRow.java \
        src/com/customer/reports/CurrencyTotal.java \
        src/com/customer/reports/Group.java \
        src/com/customer/reports/Grouping.java \
        test/GroupingTest.java
  java -cp out com.customer.reports.GroupingTest
  ```

- **C1/C2/C4: compile-checked, not executed.** `OverdueUnsettledTradesReport`
  compiles cleanly (no syntax or signature errors) against a minimal
  `com.olf.openjvs` API mirror built from the knowledge base's documented
  signatures — `Table`, `DBase`, `Report`, `OCalendar`, `Util`, `Logging`,
  `IScript.execute(IContainerContext)`, and the two annotation/enum sets.
  There is no Endur/Findur session in this environment, so the in-session
  integration tests and the read-only assertion (architecture §9) have not
  been run; they need a sandbox session with the §9 seed set.

## Open items carried (architecture §10)

- **A1** party name: `short_name`, fallback `long_name` (implemented that way).
- **A2** sink: text report file (implemented); Reporting Desktop is a
  configuration alternative on the same rendered `Table`.
- **A3** status id: `TRAN_STATUS_VALIDATED = 3` — one named constant in C2.
- **A4** join map: `external_bunit → party.party_id`,
  `currency → currency.id_number` — confirm against the installation's data
  dictionary.
