package com.customer.reports;

import com.olf.openjvs.*;
import com.olf.openjvs.enums.*;

/*
 * Issue #1 - Report of unsettled trades past their settlement date.
 *
 * A read-only, on-demand report for the operations desk: live (Validated)
 * trades whose settlement date is strictly before the chosen business date,
 * grouped by counterparty with a trade count and per-currency total
 * outstanding, groups ordered by total outstanding, largest first.
 *
 * Implements the two documents on this branch:
 *   docs/functional/issue-1.md    behaviour, rules, edge cases
 *   docs/architecture/issue-1.md  components C1-C4, data model, decisions
 *
 * Component mapping (architecture document section 3):
 *   C1  parameter      resolveBusinessDate()    business date, default today
 *   C2  selection      selectOverdueTrades()    the single read-only SELECT
 *   C3  aggregation    Grouping.groupByCounterparty()   groups / totals / order
 *   C4  presentation   render()                 grouped layout -> text report
 *
 * Read-only by construction (decision D7): the only data access is the C2
 * SELECT; the only write is the report artifact produced by the Report class.
 * The platform logging facility (Logging.init / info / error / close) is the
 * knowledge base's standard script pattern; if a deployment does not resolve
 * Logging with the com.olf.openjvs wildcard import, swap it for
 * Util.errorLogMessage - the report itself does not depend on it.
 */
@ScriptAttributes(allowNativeExceptions = false)
@PluginCategory(SCRIPT_CATEGORY_ENUM.SCRIPT_CAT_GENERIC)
public class OverdueUnsettledTradesReport implements IScript {

    /*
     * Selection constants (architecture document 6.1 predicate).
     * Status id 3 = Validated, per the system's standard status list (KB:
     * ICE ECMS and TradeVault Installation Guide, 10.8.2, cited by the
     * functional document section 2). If an installation maps ids
     * differently, the fix is this one constant (open item A3, decision D8).
     */
    static final int TRAN_STATUS_VALIDATED = 3;   /* live deal: "status of deals that are live and are still active" */
    static final int TRADE_FLAG_LIVE = 1;         /* live deal: "not an authorization or holding record" (KB: Database Purging) */
    static final int CURRENT_VERSION = 1;         /* current version of the deal (KB: History Tables) */

    /* The report artifact's file name (no path - the Report class places it in the session's report directory). */
    static final String REPORT_FILE = "unsettled_overdue_trades.txt";

    public void execute(IContainerContext context) throws OException {
        run(context);
    }

    /*
     * One run, C1 -> C2 -> C3 -> C4 (architecture document section 5).
     * Returns nothing: the deliverable is the report artifact.
     */
    private void run(IContainerContext context) throws OException {
        Logging.init(this.getClass(), "UNSETTLED_TRADES_REPORT", "OverdueUnsettledTradesReport");
        try {
            int businessDate = resolveBusinessDate(context);      /* C1 */
            Logging.info("Running unsettled-overdue-trades report for business date "
                         + OCalendar.formatJd(businessDate));

            Table rows = selectOverdueTrades(businessDate);       /* C2 */
            try {
                Group[] groups = groupByCounterparty(rows, businessDate);   /* C3 */
                int tradeCount = 0;
                for (int i = 0; i < groups.length; i++) {
                    tradeCount += groups[i].tradeCount;
                }
                Logging.info("Selected " + tradeCount + " trade(s) in " + groups.length
                             + " counterparty group(s)");

                render(groups, businessDate);                     /* C4 */
                Logging.info("Report written: " + REPORT_FILE);
            } finally {
                rows.destroy();
            }
        } catch (OException oex) {
            Logging.error("Error: " + oex.getMessage());
            throw oex;
        } finally {
            Logging.close();
        }
    }

    /* C1 - parameter ------------------------------------------------------------ */

    /*
     * C1 - business date parameter (architecture document section 3, C1).
     *
     * Contract: one parameter, business date; default the session's business
     * date ("today" in the system's own idiom - functional document section 4,
     * rule 1); validate; hand one date value downstream. No data access.
     *
     * The value is taken from the plugin argument table when one is supplied
     * (a business_date column, or the documented parameter_name /
     * parameter_value row pair); otherwise the session business date is the
     * default. Accepted forms: a Julian integer, or a date string the
     * platform can parse. An unparseable value is an explicit parameter
     * error (C1 contract), not a run with the wrong date.
     */
    private int resolveBusinessDate(IContainerContext context) throws OException {
        String raw = argTableValue(context);
        if (raw == null || raw.trim().length() == 0) {
            return Util.getBusinessDate();
        }
        String s = raw.trim();
        try {
            if (isYYYYMMDD(s)) {
                return OCalendar.convertYYYYMMDDToJd(s);
            }
            return OCalendar.parseString(s);
        } catch (OException oex) {
            Util.exitFail("business_date parameter '" + raw + "' is not a valid business date");
            throw oex;   /* unreachable: exitFail terminates the run */
        }
    }

    /* "YYYYMMDD" - the system's Julian-date SQL idiom. */
    private static boolean isYYYYMMDD(String s) {
        if (s.length() != 8) {
            return false;
        }
        for (int i = 0; i < 8; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /*
     * Read the business_date value from the plugin argument table, if one was
     * supplied with this run. Returns null when no argument table or no
     * business_date value is present, so the caller applies the default.
     */
    private String argTableValue(IContainerContext context) throws OException {
        Table argt = null;
        try {
            argt = context.getArgumentsTable();
        } catch (OException oex) {
            return null;   /* no argument mechanism in this invocation -> default */
        }
        if (argt == null) {
            return null;
        }

        /* Shape 1: a business_date column (the value in that column). */
        try {
            String v = argt.getString("business_date", 1);
            if (v != null && v.length() > 0) {
                return v;
            }
        } catch (OException oex) {
            try {
                /* the parameter may have been passed as a Julian integer */
                return String.valueOf(argt.getInt("business_date", 1));
            } catch (OException oex2) {
                /* column absent - try the next shape */
            }
        }

        /*
         * Shape 2: parameter_name / parameter_value rows - the documented
         * Operation Services parameter-table shape.
         */
        try {
            int n = argt.getNumRows();
            for (int r = 1; r <= n; r++) {
                if ("business_date".equals(argt.getString("parameter_name", r))) {
                    String v = argt.getString("parameter_value", r);
                    if (v != null) {
                        return v;
                    }
                }
            }
        } catch (OException oex) {
            /* shape absent - no value */
        }
        return null;
    }

    /* C2 - selection -------------------------------------------------------------- */

    /*
     * C2 - selection: the single read-only SELECT (architecture document 6.1).
     *
     * A trade appears iff (functional document section 4, rule 2, section 5):
     *   - transaction status Validated (live)
     *   - trade_flag = 1 (the system's live-deal convention)
     *   - current_flag = 1 (the deal's current version; superseded Amended
     *     versions are excluded doubly - by status and by version flag)
     *   - settle_date present and strictly before the business date
     * Cancelled / Matured / transitional deals never pass the status
     * predicate. A zero-outstanding trade passes (no position filter - Q4).
     * An empty result is a normal outcome (D10).
     */
    private Table selectOverdueTrades(int businessDate) throws OException {
        /*
         * A date string that compares correctly against the platform's date
         * columns in SQL (OCalendar.formatJdForDbAccess is documented for
         * exactly this; the system's own scripts build ab_tran date
         * predicates this way - KB: OpenJVS Programmers Guide).
         */
        String settleBefore = OCalendar.formatJdForDbAccess(businessDate);

        String sql
            = "SELECT a.tran_num,"
            + " a.deal_tracking_num,"
            + " a.external_bunit,"
            + " p.short_name,"
            + " p.long_name,"
            + " a.ins_num,"
            + " a.settle_date,"
            + " a.position,"
            + " a.unit,"
            + " c.name"
            + " FROM ab_tran a"
            + " JOIN party p ON a.external_bunit = p.party_id"
            + " JOIN currency c ON a.currency = c.id_number"
            + " WHERE a.tran_status = " + TRAN_STATUS_VALIDATED
            + "   AND a.trade_flag = " + TRADE_FLAG_LIVE
            + "   AND a.current_flag = " + CURRENT_VERSION
            + "   AND a.settle_date IS NOT NULL"
            + "   AND a.settle_date < '" + settleBefore + "'";

        Logging.info("C2 selection: " + sql);

        Table rows = Table.tableNew("Overdue unsettled trades");
        DBase.runSql(sql);
        DBase.createTableOfQueryResults(rows);
        return rows;
    }

    /* C3 - aggregation -------------------------------------------------------------- */

    /*
     * C3 - aggregation over the C2 Table (decision D3: in memory, not in SQL,
     * so the business rules are unit-testable over a synthetic fixture).
     *
     * Contract (architecture document section 3, C3): group by counterparty;
     * count; per-currency sums only; ordering key; order groups and trades;
     * days overdue per trade. The platform-specific reading of the Table is
     * here; the business rules themselves are the pure functions in
     * Grouping (no com.olf types).
     */
    private Group[] groupByCounterparty(Table rows, int businessDate) throws OException {
        int n = rows.getNumRows();
        TradeRow[] tradeRows = new TradeRow[n];
        for (int r = 1; r <= n; r++) {
            TradeRow tr = new TradeRow();
            tr.tranNum = rows.getInt("tran_num", r);
            tr.dealTrackingNum = rows.getInt("deal_tracking_num", r);
            tr.bunit = rows.getInt("external_bunit", r);
            /* A1: short_name, fallback long_name when absent. */
            tr.counterparty = nonEmpty(rows.getString("short_name", r), rows.getString("long_name", r));
            tr.insNum = rows.getInt("ins_num", r);
            tr.settleDate = rows.getCellDate("settle_date", r);
            tr.position = rows.getDouble("position", r);
            /* ab_tran.unit is the "unit or measure" of the amount; its column type is not settled in the KB, so read it type-flexibly. */
            tr.unit = cellAsString(rows, "unit", r);
            tr.currency = nonEmpty(rows.getString("name", r), "???");
            tr.daysOverdue = Grouping.daysOverdue(businessDate, tr.settleDate);
            tradeRows[r - 1] = tr;
        }
        return Grouping.groupByCounterparty(tradeRows);
    }

    /* First non-empty of the two, or the third. */
    private String nonEmpty(String first, String second, String third) {
        if (first != null && first.length() > 0) return first;
        if (second != null && second.length() > 0) return second;
        return third;
    }

    private String nonEmpty(String first, String second) {
        return nonEmpty(first, second, "");
    }

    /* Read a cell as a string regardless of its declared type. */
    private String cellAsString(Table rows, String col, int r) throws OException {
        try {
            String s = rows.getString(col, r);
            if (s != null) {
                return s;
            }
        } catch (OException oex) {
            /* typed numeric cell - fall through */
        }
        try {
            return String.valueOf(rows.getDouble(col, r));
        } catch (OException oex) {
            return String.valueOf(rows.getInt(col, r));
        }
    }

    /* C4 - presentation / output adapter -------------------------------------------- */

    /*
     * C4 - presentation / output adapter (architecture document section 3,
     * C4).
     *
     * Lays out the ordered groups - one block per counterparty: the group
     * line, one total line per currency present, then the group's trade lines,
     * in the order C3 produced - into a single presentation Table, and
     * delivers it as a text report file via the Report class (the default
     * sink; the Reporting Desktop alternative, open item A2, is a
     * configuration change on this same rendered table).
     *
     * The only component that writes - and it writes a report artifact, never
     * a database record (D7). An empty group list renders an empty report:
     * a valid outcome, not an error (functional document section 4, rule 10;
     * decision D10).
     *
     * Returns the report file name.
     */
    private String render(Group[] groups, int businessDate) throws OException {
        Table out = buildPresentationTable(groups);
        try {
            /* The report title carries the business date (architecture document section 3, C4). */
            Report.reportStart(REPORT_FILE,
                               "Unsettled trades past settlement date - business date "
                               + OCalendar.formatJd(businessDate));

            /* Presentation (KB report idiom: widths set before the print). */
            out.formatSetWidth("COUNTERPARTY", 24);
            out.formatSetWidth("CURRENCY", 10);
            out.formatSetWidth("AMOUNT", 16);
            out.formatSetJustifyLeft("TRADE_NO");

            Report.printTableToReport(out, REPORT_ADD_ENUM.FIRST_PAGE);
            Report.reportEnd();
        } finally {
            out.destroy();
        }
        return REPORT_FILE;
    }

    /*
     * The grouped layout, one rectangular Table (so the Report class prints
     * it in a single pass): per counterparty, a GROUP line, one TOTAL line
     * per currency present, then the TRADE lines. Every row carries the
     * counterparty name, so each line is self-describing on its own.
     */
    private Table buildPresentationTable(Group[] groups) throws OException {
        Table out = Table.tableNew("Unsettled overdue trades presentation");
        out.addCol("COUNTERPARTY", COL_TYPE_ENUM.COL_STRING);
        out.addCol("ROW_KIND", COL_TYPE_ENUM.COL_STRING);
        out.addCol("TRADES_IN_GROUP", COL_TYPE_ENUM.COL_INT);
        out.addCol("DEAL_NO", COL_TYPE_ENUM.COL_STRING);
        out.addCol("TRADE_NO", COL_TYPE_ENUM.COL_STRING);
        out.addCol("INSTRUMENT", COL_TYPE_ENUM.COL_STRING);
        out.addCol("SETTLE_DATE", COL_TYPE_ENUM.COL_STRING);
        out.addCol("DAYS_OVERDUE", COL_TYPE_ENUM.COL_INT);
        out.addCol("CURRENCY", COL_TYPE_ENUM.COL_STRING);
        out.addCol("AMOUNT", COL_TYPE_ENUM.COL_DOUBLE);
        out.addCol("UNIT", COL_TYPE_ENUM.COL_STRING);

        for (int gi = 0; gi < groups.length; gi++) {
            Group g = groups[gi];

            /* Group line: the trade count and the group's total outstanding - its largest per-currency total, the ordering key (functional document 4, rule 7). */
            out.addRow(new Object[]{
                g.name, "GROUP", g.tradeCount, "", "", "", "", 0, "", g.orderKey, "" });

            /* One total line per currency present. */
            for (int ti = 0; ti < g.totals.length; ti++) {
                CurrencyTotal t = g.totals[ti];
                out.addRow(new Object[]{
                    g.name, "TOTAL", g.tradeCount, "", "", "", "", 0, t.currency, t.amount, "" });
            }

            /* The group's trade lines, oldest settlement date first (functional document 4, rule 8). */
            for (int ri = 0; ri < g.trades.length; ri++) {
                TradeRow tr = g.trades[ri];
                out.addRow(new Object[]{
                    g.name, "TRADE", g.tradeCount,
                    String.valueOf(tr.dealTrackingNum),
                    String.valueOf(tr.tranNum),
                    String.valueOf(tr.insNum),
                    OCalendar.formatJd(tr.settleDate),
                    tr.daysOverdue,
                    tr.currency,
                    tr.position,
                    tr.unit == null ? "" : tr.unit });
            }
        }
        return out;
    }
}
