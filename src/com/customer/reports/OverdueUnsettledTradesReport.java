package com.customer.reports;

import com.olf.openjvs.*;
import com.olf.openjvs.enums.*;
import java.util.*;

/**
 * Report of trades past their settlement date that are still unsettled, grouped
 * by counterparty.
 *
 * One JVS report script (implements {@code IScript}), run on demand for a chosen
 * business date that defaults to the system's current business date. It is
 * read-only: it selects and presents, and never changes a trade, sets a status,
 * or writes anything back.
 *
 * The behaviour, rules and edge cases are the functional specification
 * (docs/functional/issue-6.md); the shape, steps and decisions are the
 * architecture (docs/architecture/issue-6.md). The steps below follow the
 * architecture's S0-S5.
 */
public class OverdueUnsettledTradesReport implements IScript
{
    // S0/D12 - the business date arrives as an AVS argument field under this name;
    // when absent the report defaults to the system's current business date.
    private static final String ARG_BUSINESS_DATE = "business_date";

    // Rule 2 (functional spec section 3) and decision D8: a trade is excluded when
    // its lifecycle status is in the terminal/void family or is a not-yet-validated
    // original. The ids come from the system status map named in the architecture
    // (section 2). It is encoded as an exclusion so that any status not on that map
    // defaults to included - a chasing report should surface an unknown state rather
    // than silently drop an obligation.
    private static final int[] VOID_OR_UNVALIDATED_STATUS =
    {
        1,   // Pending
        2,   // New
        5,   // Cancelled
        6,   // Buyout
        7,   // Proposed
        10,  // Amended (superseded original)
        14,  // Deleted
        15,  // Template
        17,  // Cancelled New
        18,  // Buyout New
        22,  // Closeout
        23,  // Split Closed
        24,  // Giveup Closed
        25,  // Amended Closed
        32,  // View Buyout
        33,  // View Novation
        35   // Exercise Cancel New
    };

    // D9 - trades with no counterparty are kept and grouped under this label.
    private static final String NO_COUNTERPARTY = "No Counterparty / Internal";

    // D3 - a trade that carries no currency is kept and subtotaled under this label.
    private static final String NO_CURRENCY = "(no currency)";

    // S2/D5 - the instrument is reported by its reference id (ins_num); resolving it
    // to a display name is deferred (the instrument field is an open item in the
    // functional specification).
    private static final String REPORT_BASENAME = "Overdue_Unsettled_Trades";
    private static final String REPORT_TITLE    = "Trades Past Settlement Date, Still Unsettled";

    public void execute(IContainerContext context) throws OException
    {
        // S0 - resolve the business date (argument, else the system's current one).
        int businessDate = resolveBusinessDate(context);

        // S1 - one read-only select: one row per reportable trade.
        Table tTrades = selectReportableTrades(businessDate);

        Table tReport = null;
        try
        {
            // S2-S4 - present the columns, group by counterparty, subtotals per
            // currency, and order the groups by total outstanding, largest first.
            tReport = buildGroupedReport(tTrades);

            // S5 - emit (report file, Excel, in-session view). A zero-row result is a
            // valid result, not an error (functional spec edge case 10).
            emitReport(tReport, businessDate);
        }
        finally
        {
            if (tReport != null)
            {
                tReport.destroy();
            }
            tTrades.destroy();
        }
    }

    // -------------------------------------------------------------------- S0
    //
    // The run is on demand for a chosen business date (functional spec section 4).
    // The date arrives as an AVS argument field; if it is not supplied the report
    // uses the system's current business date - the Business Date setting maintained
    // in the System Date Roll, not the wall clock.

    private int resolveBusinessDate(IContainerContext context) throws OException
    {
        int businessDate = readArgumentBusinessDate(context);
        if (businessDate > 0)
        {
            return businessDate;
        }

        Table tCfg = Table.tableNew("business_date");
        try
        {
            DBase.runSql("select business_date from configuration");
            DBase.createTableOfQueryResults(tCfg);
            businessDate = tCfg.getInt(ARG_BUSINESS_DATE, 1);
        }
        finally
        {
            tCfg.destroy();
        }

        if (businessDate <= 0)
        {
            throw new OException(
                "Overdue unsettled trades report: no business date supplied and no "
                + "configuration.business_date to default to.");
        }
        return businessDate;
    }

    private int readArgumentBusinessDate(IContainerContext context) throws OException
    {
        if (context == null)
        {
            return 0;
        }
        Table argt = context.getArgumentsTable();
        if (argt == null || argt.getNumRows() < 1)
        {
            return 0;
        }
        try
        {
            // A business date is a Julian integer, so the argument field is an int.
            return argt.getInt(ARG_BUSINESS_DATE, 1);
        }
        catch (OException e)
        {
            // No business_date argument (column absent or not supplied): fall back to
            // the system's current business date.
            return 0;
        }
    }

    // -------------------------------------------------------------------- S1
    //
    // One read-only select. A trade is reportable when, as of the business date:
    //   rule 1 - settle_date is strictly before the business date
    //            (functional spec rule 1; edge case 11: a trade settling on the
    //             business date is not yet overdue and is excluded);
    //   rule 2 - it is still a live obligation, i.e. its status is not in the
    //            terminal/void or not-yet-validated family (D8);
    //   rule 3 - it still carries a remaining amount, position <> 0 (D1/D2; a
    //             fully-settled trade carries zero and drops out on its own).
    //
    // The counterparty name and the currency code are resolved here by a left join,
    // so that a trade with no counterparty (D9) or no currency (D3) is still
    // retained rather than dropped by an inner join.

    private Table selectReportableTrades(int businessDate) throws OException
    {
        StringBuilder in = new StringBuilder();
        for (int status : VOID_OR_UNVALIDATED_STATUS)
        {
            if (in.length() > 0)
            {
                in.append(",");
            }
            in.append(status);
        }

        String sql =
            "select a.tran_num," +
            "       a.deal_num," +
            "       coalesce(b.bunit_name, '" + NO_COUNTERPARTY + "') as counterparty," +
            "       a.ins_num," +
            "       a.settle_date," +
            "       " + businessDate + " - a.settle_date as days_overdue," +
            "       a.position as outstanding," +
            "       coalesce(c.name, '') as currency" +
            "  from ab_tran a" +
            "  left outer join bunit b on a.external_bunit = b.id" +
            "  left outer join currency c on a.currency = c.id_number" +
            " where a.settle_date < " + businessDate +
            "   and (a.tran_status is null or a.tran_status not in (" + in + "))" +
            "   and a.position <> 0" +
            " order by a.settle_date, a.tran_num";

        Table tTrades = Table.tableNew("Overdue Unsettled Trades");
        DBase.runSql(sql);
        DBase.createTableOfQueryResults(tTrades);
        return tTrades;
    }

    // ---------------------------------------------------------------- S2-S4
    //
    // S2 presents the columns; S3 gives, for each counterparty, a count of unsettled
    // trades and the outstanding broken down per currency (D3 - a single total is
    // only meaningful per currency); S4 orders the groups by total outstanding,
    // largest first, tie-broken by higher count then counterparty name (D7).
    //
    // The ordering by subtotal is done in the script, not delegated to the
    // platform: the platform's documented summing is tied to ascending key order and
    // cannot order a group by the value of its subtotal (the architecture notes the
    // same for the view-layer sort). The per-currency subtotal values are plain sums
    // of the outstanding column and are identical to the platform's; only the order
    // is computed here, where it cannot be done on the platform.

    private Table buildGroupedReport(Table tTrades) throws OException
    {
        int rows = tTrades.getNumRows();

        // Per counterparty: the outstanding summed by currency, the trade count by
        // currency, and the total trade count. Declared final so the ordering below
        // can read them from the comparator on any Java version.
        final Map<String, Map<String, Double>> sumByCcy   = new LinkedHashMap<String, Map<String, Double>>();
        final Map<String, Map<String, Integer>> countByCcy = new LinkedHashMap<String, Map<String, Integer>>();
        final Map<String, Integer> totalTrades             = new LinkedHashMap<String, Integer>();

        for (int r = 1; r <= rows; r++)
        {
            String cp  = nullToEmpty(tTrades.getString("counterparty", r));
            String ccy  = nullToEmpty(tTrades.getString("currency", r));
            if (ccy.length() == 0)
            {
                ccy = NO_CURRENCY;
            }
            double amount = tTrades.getDouble("outstanding", r);

            Map<String, Double> sum = sumByCcy.get(cp);
            if (sum == null)
            {
                sum = new LinkedHashMap<String, Double>();
                sumByCcy.put(cp, sum);
            }
            Double s = sum.get(ccy);
            sum.put(ccy, (s == null ? 0.0 : s) + amount);

            Map<String, Integer> count = countByCcy.get(cp);
            if (count == null)
            {
                count = new LinkedHashMap<String, Integer>();
                countByCcy.put(cp, count);
            }
            Integer c = count.get(ccy);
            count.put(ccy, (c == null ? 0 : c) + 1);

            Integer total = totalTrades.get(cp);
            totalTrades.put(cp, (total == null ? 0 : total) + 1);
        }

        // D7 ordering: largest single-currency subtotal (desc), then higher trade
        // count (desc), then counterparty name (asc).
        List<String> order = new ArrayList<String>(sumByCcy.keySet());
        Collections.sort(order, new Comparator<String>()
        {
            public int compare(String a, String b)
            {
                int byTotal = Double.compare(maxCcy(sumByCcy.get(b)), maxCcy(sumByCcy.get(a)));
                if (byTotal != 0)
                {
                    return byTotal;
                }
                int byCount = Integer.compare(totalTrades.get(b), totalTrades.get(a));
                if (byCount != 0)
                {
                    return byCount;
                }
                return a.compareToIgnoreCase(b);
            }
        });

        // Build the report in that order.
        Table tReport = Table.tableNew(REPORT_TITLE);
        tReport.addCol("record",       COL_TYPE_ENUM.COL_STRING);
        tReport.addCol("tran_num",     COL_TYPE_ENUM.COL_INT);
        tReport.addCol("deal_num",     COL_TYPE_ENUM.COL_INT);
        tReport.addCol("counterparty", COL_TYPE_ENUM.COL_STRING);
        tReport.addCol("ins_num",      COL_TYPE_ENUM.COL_INT);
        tReport.addCol("settle_date",  COL_TYPE_ENUM.COL_INT);
        tReport.addCol("days_overdue", COL_TYPE_ENUM.COL_INT);
        tReport.addCol("outstanding",  COL_TYPE_ENUM.COL_DOUBLE);
        tReport.addCol("currency",     COL_TYPE_ENUM.COL_STRING);
        tReport.addCol("count",        COL_TYPE_ENUM.COL_INT);

        int outRow = 0;
        for (String cp : order)
        {
            // One detail row per trade for this counterparty, in trade order.
            for (int r = 1; r <= rows; r++)
            {
                if (!cp.equals(nullToEmpty(tTrades.getString("counterparty", r))))
                {
                    continue;
                }
                outRow++;
                tReport.addRow();
                tReport.setString("record",       outRow, "Trade");
                tReport.setInt("tran_num",        outRow, tTrades.getInt("tran_num", r));
                tReport.setInt("deal_num",        outRow, tTrades.getInt("deal_num", r));
                tReport.setString("counterparty", outRow, cp);
                tReport.setInt("ins_num",         outRow, tTrades.getInt("ins_num", r));
                tReport.setInt("settle_date",     outRow, tTrades.getInt("settle_date", r));
                tReport.setInt("days_overdue",    outRow, tTrades.getInt("days_overdue", r));
                tReport.setDouble("outstanding",  outRow, tTrades.getDouble("outstanding", r));
                tReport.setString("currency",     outRow, nullToEmpty(tTrades.getString("currency", r)));
            }

            // The group's total outstanding, one subtotal row per currency (D3).
            Map<String, Double> sum   = sumByCcy.get(cp);
            Map<String, Integer> cnt = countByCcy.get(cp);
            for (Map.Entry<String, Double> entry : sum.entrySet())
            {
                outRow++;
                tReport.addRow();
                tReport.setString("record",       outRow, "Subtotal");
                tReport.setString("counterparty", outRow, cp);
                tReport.setDouble("outstanding",  outRow, entry.getValue());
                tReport.setString("currency",     outRow, entry.getKey());
                tReport.setInt("count",           outRow, cnt.get(entry.getKey()));
            }

            // The group's count of unsettled trades.
            outRow++;
            tReport.addRow();
            tReport.setString("record",       outRow, "Total");
            tReport.setString("counterparty", outRow, cp);
            tReport.setInt("count",           outRow, totalTrades.get(cp));
        }

        // An overall grand total, across all counterparties (optional per the
        // architecture, kept because it costs nothing): the total count and the
        // outstanding summed per currency. Only when there is at least one trade -
        // an empty result is a zero-row report with no totals (edge case 10).
        if (!sumByCcy.isEmpty())
        {
            int grandCount = 0;
            Map<String, Double> grandByCcy = new LinkedHashMap<String, Double>();
            for (Map<String, Double> group : sumByCcy.values())
            {
                for (Map.Entry<String, Double> entry : group.entrySet())
                {
                    String ccy = entry.getKey();
                    Double s = grandByCcy.get(ccy);
                    grandByCcy.put(ccy, (s == null ? 0.0 : s) + entry.getValue());
                }
            }
            for (Integer n : totalTrades.values())
            {
                grandCount += n;
            }

            outRow++;
            tReport.addRow();
            tReport.setString("record", outRow, "Grand Total");
            tReport.setInt("count", outRow, grandCount);
            for (Map.Entry<String, Double> entry : grandByCcy.entrySet())
            {
                outRow++;
                tReport.addRow();
                tReport.setString("record",       outRow, "Grand Total");
                tReport.setDouble("outstanding",  outRow, entry.getValue());
                tReport.setString("currency",     outRow, entry.getKey());
            }
        }

        applyTitles(tReport);
        return tReport;
    }

    // S2 - set the display titles on the report columns.

    private void applyTitles(Table tReport)
    {
        tReport.setColTitle("record",       "Row");
        tReport.setColTitle("tran_num",     "Trade");
        tReport.setColTitle("deal_num",     "Deal");
        tReport.setColTitle("counterparty", "Counterparty");
        tReport.setColTitle("ins_num",      "Instrument");
        tReport.setColTitle("settle_date",  "Settlement\nDate");
        tReport.setColTitle("days_overdue", "Days\nOverdue");
        tReport.setColTitle("outstanding",  "Amount\nOutstanding");
        tReport.setColTitle("currency",     "Currency");
        tReport.setColTitle("count",        "Count");
    }

    // -------------------------------------------------------------------- S5
    //
    // Emit the report: a report file and an Excel save for the desk, plus an
    // in-session table view. The business date is in the file names so repeated runs
    // do not overwrite one another. A zero-row report is emitted as-is.

    private void emitReport(Table tReport, int businessDate) throws OException
    {
        String base = REPORT_BASENAME + "_" + businessDate;

        Report.reportStart(base + ".csv", REPORT_TITLE + " - Business Date " + businessDate);
        Report.printTableToReport(tReport, REPORT_ADD_ENUM.FIRST_PAGE);
        Report.reportEnd();

        tReport.excelSave(base + ".xls", "OverdueUnsettled", "A1");
        tReport.viewTable();
    }

    // ---------------------------------------------------------------- helpers

    private static String nullToEmpty(String s)
    {
        return s == null ? "" : s;
    }

    private static double maxCcy(Map<String, Double> byCurrency)
    {
        double max = Double.NEGATIVE_INFINITY;
        for (Double value : byCurrency.values())
        {
            if (value > max)
            {
                max = value;
            }
        }
        // position is summed as stored (signed, D3), so the largest subtotal is the
        // maximum of the signed values, not clamped at zero.
        return max == Double.NEGATIVE_INFINITY ? 0.0 : max;
    }
}
