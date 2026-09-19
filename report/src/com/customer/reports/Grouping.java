package com.customer.reports;

/*
 * C3 (aggregation) - the pure core of the report, per the two documents on
 * this branch:
 *
 *   docs/functional/issue-1.md    behaviour, rules, edge cases
 *   docs/architecture/issue-1.md  component C3 and its boundary
 *
 * Deliberately plain functions over plain arrays, with no platform
 * (com.olf.openjvs) types, so the business rules below are unit testable
 * without a live Endur/Findur session (architecture document section 9,
 * "Unit (no session required)"):
 *
 *   - group by counterparty business unit
 *   - count trades per group
 *   - sum outstanding within each currency only; opposite sides offset
 *   - ordering key = the group's largest per-currency total
 *   - groups ordered by ordering key descending, ties by counterparty name
 *   - trades within a group ordered by settle date ascending, then trade id
 *   - days overdue = whole calendar days between settle date and business date
 *
 * Empty input yields an empty group array: a normal result, not an error
 * (functional document section 4, rule 10; architecture decision D10).
 *
 * The input row set is preserved exactly: every input trade appears in
 * exactly one group (architecture document section 3, C3 contract).
 */
class Grouping {

    /*
     * Whole calendar days between two Julian business dates.
     *
     * Julian dates are consecutive day numbers, so the difference is the
     * calendar-day count exactly (settle 2026-09-10, business 2026-09-19 -> 9,
     * the functional document's own example, section 4, rule 4).
     *
     * Isolated in one function per decision D5: a business-day basis (Q7)
     * would be a swap of this function only, with no change to selection or
     * presentation.
     */
    static int daysOverdue(int businessDate, int settleDate) {
        return businessDate - settleDate;
    }

    /*
     * Group the selected trades by counterparty business unit, total
     * outstanding per currency, and order groups and trades per the
     * functional document (section 4, rules 5-8).
     */
    static Group[] groupByCounterparty(TradeRow[] rows) {
        if (rows.length == 0) {
            return new Group[0];
        }

        /* Pass 1: one group per counterparty business unit; discover and count. */
        Group[] groups = new Group[0];
        for (int i = 0; i < rows.length; i++) {
            TradeRow row = rows[i];
            int gi = findGroup(groups, row.bunit);
            Group g;
            if (gi < 0) {
                g = new Group();
                g.bunit = row.bunit;
                g.name = "";
                g.totals = new CurrencyTotal[0];
                g.trades = new TradeRow[0];
                Group[] grown = new Group[groups.length + 1];
                System.arraycopy(groups, 0, grown, 0, groups.length);
                grown[groups.length] = g;
                groups = grown;
            } else {
                g = groups[gi];
            }
            g.tradeCount++;

            /* Per-currency sum; opposite sides offset arithmetically (rule 7). */
            g.totals = addAmount(g.totals, row.currency, row.position);
            g.trades = addTrade(g.trades, row);

            /* Display name: first non-empty wins (short_name, fallback long_name - A1). */
            if (g.name.length() == 0 && row.counterparty != null) {
                g.name = row.counterparty;
            }
        }

        /* Pass 2: per-currency order, ordering key, trade order. */
        for (int gi = 0; gi < groups.length; gi++) {
            Group g = groups[gi];
            sortTotals(g.totals);
            g.orderKey = largestTotal(g.totals);
            sortTrades(g.trades);
        }
        sortGroups(groups);
        return groups;
    }

    /* Append-or-accumulate one trade's amount into the group's per-currency totals. */
    static CurrencyTotal[] addAmount(CurrencyTotal[] totals, String currency, double amount) {
        for (int i = 0; i < totals.length; i++) {
            if (totals[i].currency.equals(currency)) {
                totals[i].amount += amount;
                return totals;
            }
        }
        CurrencyTotal[] grown = new CurrencyTotal[totals.length + 1];
        System.arraycopy(totals, 0, grown, 0, totals.length);
        CurrencyTotal t = new CurrencyTotal();
        t.currency = currency;
        t.amount = amount;
        grown[totals.length] = t;
        return grown;
    }

    /* Append one trade to the group's trade list. */
    static TradeRow[] addTrade(TradeRow[] trades, TradeRow row) {
        TradeRow[] grown = new TradeRow[trades.length + 1];
        System.arraycopy(trades, 0, grown, 0, trades.length);
        grown[trades.length] = row;
        return grown;
    }

    /* Index of the group with this business unit, or -1 if none. */
    static int findGroup(Group[] groups, int bunit) {
        for (int i = 0; i < groups.length; i++) {
            if (groups[i].bunit == bunit) {
                return i;
            }
        }
        return -1;
    }

    /* The group's largest per-currency total (rule 7: the "total outstanding" used for ordering). */
    static double largestTotal(CurrencyTotal[] totals) {
        boolean any = false;
        double largest = 0.0;
        for (int i = 0; i < totals.length; i++) {
            if (!any || totals[i].amount > largest) {
                largest = totals[i].amount;
                any = true;
            }
        }
        return largest;
    }

    /*
     * Groups: ordering key descending, ties by counterparty name ascending
     * (functional document section 4, rule 8).
     */
    static void sortGroups(Group[] groups) {
        for (int i = 1; i < groups.length; i++) {
            Group key = groups[i];
            int j = i - 1;
            while (j >= 0 && compareGroups(groups[j], key) > 0) {
                groups[j + 1] = groups[j];
                j--;
            }
            groups[j + 1] = key;
        }
    }

    static int compareGroups(Group a, Group b) {
        if (a.orderKey != b.orderKey) {
            return a.orderKey < b.orderKey ? 1 : -1;   /* descending */
        }
        return compareNames(a.name, b.name);            /* ascending tie-break */
    }

    static int compareNames(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.compareTo(b);
    }

    /*
     * Trades within a group: settle date ascending (oldest / longest overdue
     * first), then trade id (functional document section 4, rule 8).
     */
    static void sortTrades(TradeRow[] trades) {
        for (int i = 1; i < trades.length; i++) {
            TradeRow key = trades[i];
            int j = i - 1;
            while (j >= 0 && compareTrades(trades[j], key) > 0) {
                trades[j + 1] = trades[j];
                j--;
            }
            trades[j + 1] = key;
        }
    }

    static int compareTrades(TradeRow a, TradeRow b) {
        if (a.settleDate != b.settleDate) {
            return a.settleDate < b.settleDate ? -1 : 1;
        }
        if (a.tranNum != b.tranNum) {
            return a.tranNum < b.tranNum ? -1 : 1;
        }
        return 0;
    }

    /* Totals: currency code ascending, so the group's total lines read in a stable order. */
    static void sortTotals(CurrencyTotal[] totals) {
        for (int i = 1; i < totals.length; i++) {
            CurrencyTotal key = totals[i];
            int j = i - 1;
            while (j >= 0 && compareTotals(totals[j], key) > 0) {
                totals[j + 1] = totals[j];
                j--;
            }
            totals[j + 1] = key;
        }
    }

    static int compareTotals(CurrencyTotal a, CurrencyTotal b) {
        return compareNames(a.currency, b.currency);
    }
}
