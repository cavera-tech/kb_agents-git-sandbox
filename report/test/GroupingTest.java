package com.customer.reports;

/*
 * Unit tests for the C3 pure core (Grouping + the row/group value objects),
 * per the architecture document section 9, "Unit (no session required)":
 * grouping; per-currency sums; offsetting; ordering-key selection; group and
 * row ordering with ties; days-overdue computation; empty input.
 *
 * Run with any JDK - no Endur/Findur session is needed, because the tested
 * classes carry no com.olf.openjvs types:
 *
 *   cd report
 *   javac -d out src/com/customer/reports/TradeRow.java \
 *         src/com/customer/reports/CurrencyTotal.java \
 *         src/com/customer/reports/Group.java \
 *         src/com/customer/reports/Grouping.java \
 *         test/GroupingTest.java
 *   java -cp out com.customer.reports.GroupingTest
 *
 * GroupingTest is public so the harness can be invoked; the tested classes
 * are package-private by design.
 */
public class GroupingTest {

    private static int failures = 0;

    public static void main(String[] args) {
        testDaysOverdue();
        testDaysOverdueBoundary();
        testEmptyInput();
        testSingleGroupSingleCurrency();
        testPerCurrencyTotalsNeverCrossCurrencies();
        testOppositeSidesOffset();
        testOrderingKeyIsLargestPerCurrencyTotal();
        testOrderingKeyOfAllNegativeTotals();
        testGroupsOrderedLargestFirst();
        testGroupTieBrokenByName();
        testTradesOrderedBySettleDateThenTradeId();
        testAllTradesPreservedExactly();
        testNameFallbackToFirstNonEmpty();
        testTotalsOrderedByCurrency();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL TESTS PASSED");
        } else {
            System.out.println(failures + " TEST(S) FAILED");
            System.exit(1);
        }
    }

    /* Functional document 4, rule 4: settle 2026-09-10, business 2026-09-19 -> 9 days. Julian dates are day numbers, so the difference is the calendar-day count. */
    static void testDaysOverdue() {
        int settle = 20000;           /* stand-in Julian for the settlement date */
        int business = settle + 9;    /* the functional document's own 9-day example */
        check(Grouping.daysOverdue(business, settle) == 9,
              "days overdue = 9 for a 9-day gap (functional document 4.4 example)");
    }

    /* A trade settling on the business date is not overdue and is not selected (functional document 4, rule 4) - that exclusion is C2's, here we only check the basis stays whole days. */
    static void testDaysOverdueBoundary() {
        int settle = 20000;
        check(Grouping.daysOverdue(settle, settle) == 0,
              "days overdue = 0 when settle date equals business date");
        check(Grouping.daysOverdue(settle + 1, settle) == 1,
              "days overdue = 1 for a one-day gap");
    }

    /* Functional document 4, rule 10 / decision D10: empty input is a normal result. */
    static void testEmptyInput() {
        Group[] groups = Grouping.groupByCounterparty(new TradeRow[0]);
        check(groups.length == 0, "empty input yields an empty group array");
    }

    static void testSingleGroupSingleCurrency() {
        TradeRow[] rows = {
            row(101, 7, "USD", 1000.0, 20000, "ACME"),
            row(102, 7, "USD", 500.0, 19999, "ACME")
        };
        Group[] groups = Grouping.groupByCounterparty(rows);
        check(groups.length == 1, "one group for one counterparty");
        Group g = groups[0];
        check(g.tradeCount == 2, "group trade count = 2");
        check(g.totals.length == 1, "one currency total");
        check(g.totals[0].currency.equals("USD"), "the total is the USD total");
        check(g.totals[0].amount == 1500.0, "USD total = 1000 + 500");
        check(g.orderKey == 1500.0, "ordering key = the USD total");
        check(g.name.equals("ACME"), "group name from the counterparty name");
    }

    /* Functional document 4, rule 7: amounts in different currencies are never added together. */
    static void testPerCurrencyTotalsNeverCrossCurrencies() {
        TradeRow[] rows = {
            row(101, 7, "USD", 100.0, 20000, "ACME"),
            row(102, 7, "EUR", 50.0, 20000, "ACME")
        };
        Group g = Grouping.groupByCounterparty(rows)[0];
        check(g.totals.length == 2, "two currency totals, one per currency");
        check(totalOf(g, "USD") == 100.0, "USD total = 100");
        check(totalOf(g, "EUR") == 50.0, "EUR total = 50");
        boolean crossed = false;
        for (int i = 0; i < g.totals.length; i++) {
            if (g.totals[i].amount == 150.0) {
                crossed = true;
            }
        }
        check(!crossed, "no single total crosses currencies (no total of 150)");
    }

    /* Functional document 4, rule 7: within one currency, opposite-side positions offset. */
    static void testOppositeSidesOffset() {
        TradeRow[] rows = {
            row(101, 7, "USD", 100.0, 20000, "ACME"),
            row(102, 7, "USD", -40.0, 20000, "ACME")
        };
        Group g = Grouping.groupByCounterparty(rows)[0];
        check(g.totals.length == 1, "one currency total");
        check(g.totals[0].amount == 60.0, "opposite sides offset: 100 + (-40) = 60");
        check(g.orderKey == 60.0, "ordering key follows the offset total");
    }

    /* Functional document 4, rule 7: the group's "total outstanding" for ordering is its largest per-currency total - not the sum across currencies. */
    static void testOrderingKeyIsLargestPerCurrencyTotal() {
        TradeRow[] rows = {
            row(101, 7, "USD", 50.0, 20000, "ACME"),
            row(102, 7, "EUR", 100.0, 20000, "ACME"),
            row(103, 7, "JPY", 30.0, 20000, "ACME")
        };
        Group g = Grouping.groupByCounterparty(rows)[0];
        check(g.orderKey == 100.0, "ordering key = largest per-currency total (100 EUR), not 180");
    }

    static void testOrderingKeyOfAllNegativeTotals() {
        TradeRow[] rows = {
            row(101, 7, "USD", -100.0, 20000, "ACME"),
            row(102, 7, "EUR", -50.0, 20000, "ACME")
        };
        Group g = Grouping.groupByCounterparty(rows)[0];
        check(g.orderKey == -50.0, "all-negative totals: ordering key = the largest value (-50)");
    }

    /* Functional document 4, rule 8: groups listed by total outstanding, largest first. */
    static void testGroupsOrderedLargestFirst() {
        TradeRow[] rows = {
            row(101, 1, "USD", 200.0, 20000, "ALPHA"),
            row(102, 2, "USD", 500.0, 20000, "BRAVO"),
            row(103, 3, "USD", 100.0, 20000, "CHARLIE")
        };
        Group[] groups = Grouping.groupByCounterparty(rows);
        check(groups.length == 3, "three groups");
        check(groups[0].bunit == 2, "largest group first (BRAVO, 500)");
        check(groups[1].bunit == 1, "second (ALPHA, 200)");
        check(groups[2].bunit == 3, "smallest last (CHARLIE, 100)");
    }

    /* Functional document 4, rule 8: ties broken by counterparty name. */
    static void testGroupTieBrokenByName() {
        TradeRow[] rows = {
            row(101, 1, "USD", 300.0, 20000, "ZULU"),
            row(102, 2, "USD", 300.0, 20000, "ALPHA"),
            row(103, 3, "USD", 300.0, 20000, "MIKE")
        };
        Group[] groups = Grouping.groupByCounterparty(rows);
        check(groups[0].name.equals("ALPHA"), "tie: ALPHA before MIKE");
        check(groups[1].name.equals("MIKE"), "tie: MIKE before ZULU");
        check(groups[2].name.equals("ZULU"), "tie: ZULU last");
    }

    /* Functional document 4, rule 8: within a group, trades by settlement date ascending, then trade id. */
    static void testTradesOrderedBySettleDateThenTradeId() {
        TradeRow[] rows = {
            row(303, 7, "USD", 10.0, 20000, "ACME"),
            row(301, 7, "USD", 10.0, 20000, "ACME"),
            row(302, 7, "USD", 10.0, 19999, "ACME")
        };
        Group g = Grouping.groupByCounterparty(rows)[0];
        check(g.trades.length == 3, "all three trades in the group");
        check(g.trades[0].tranNum == 302, "oldest settlement date first (19999)");
        check(g.trades[1].tranNum == 301, "same date: trade id ascending (301)");
        check(g.trades[2].tranNum == 303, "same date: trade id ascending (303)");
    }

    /* Architecture document section 3, C3 contract: preserves the input row set exactly. */
    static void testAllTradesPreservedExactly() {
        TradeRow[] rows = {
            row(101, 1, "USD", 10.0, 20000, "A"),
            row(102, 1, "EUR", 20.0, 20000, "A"),
            row(103, 2, "USD", 30.0, 19999, "B"),
            row(104, 2, "GBP", 40.0, 19998, "B"),
            row(105, 2, "USD", 50.0, 19997, "B"),
            row(106, 3, "USD", 60.0, 19996, "C")
        };
        Group[] groups = Grouping.groupByCounterparty(rows);
        int total = 0;
        java.util.TreeSet seen = new java.util.TreeSet();
        for (int i = 0; i < groups.length; i++) {
            total += groups[i].tradeCount;
            for (int j = 0; j < groups[i].trades.length; j++) {
                seen.add(Integer.valueOf(groups[i].trades[j].tranNum));
            }
        }
        check(total == 6, "every input trade appears exactly once");
        boolean ok = true;
        int[] expected = {101, 102, 103, 104, 105, 106};
        for (int i = 0; i < expected.length; i++) {
            if (!seen.contains(Integer.valueOf(expected[i]))) {
                ok = false;
            }
        }
        check(ok && seen.size() == 6, "no trade lost or duplicated");
    }

    /* A1: the group name is the first non-empty counterparty name of its rows (short_name, fallback long_name). */
    static void testNameFallbackToFirstNonEmpty() {
        TradeRow[] rows = {
            row(101, 7, "USD", 10.0, 20000, ""),     /* name absent on the first row */
            row(102, 7, "USD", 20.0, 20000, "ACME")  /* present on the second */
        };
        Group g = Grouping.groupByCounterparty(rows)[0];
        check(g.name.equals("ACME"), "group name falls back to the first non-empty name");
    }

    /* Total lines read in a stable order: currency code ascending. */
    static void testTotalsOrderedByCurrency() {
        TradeRow[] rows = {
            row(101, 7, "USD", 10.0, 20000, "ACME"),
            row(102, 7, "EUR", 20.0, 20000, "ACME"),
            row(103, 7, "GBP", 30.0, 20000, "ACME")
        };
        Group g = Grouping.groupByCounterparty(rows)[0];
        check(g.totals[0].currency.equals("EUR"), "EUR first");
        check(g.totals[1].currency.equals("GBP"), "GBP second");
        check(g.totals[2].currency.equals("USD"), "USD third");
    }

    /* ------------------------------------------------------------------ */

    static TradeRow row(int tranNum, int bunit, String ccy, double pos, int settle, String name) {
        TradeRow tr = new TradeRow();
        tr.tranNum = tranNum;
        tr.dealTrackingNum = tranNum / 100;
        tr.bunit = bunit;
        tr.counterparty = name;
        tr.insNum = tranNum % 97;
        tr.settleDate = settle;
        tr.position = pos;
        tr.unit = "unit";
        tr.currency = ccy;
        tr.daysOverdue = 0;
        return tr;
    }

    static double totalOf(Group g, String ccy) {
        for (int i = 0; i < g.totals.length; i++) {
            if (g.totals[i].currency.equals(ccy)) {
                return g.totals[i].amount;
            }
        }
        return Double.NaN;
    }

    static void check(boolean cond, String msg) {
        if (cond) {
            System.out.println("ok:   " + msg);
        } else {
            failures++;
            System.out.println("FAIL: " + msg);
        }
    }
}
