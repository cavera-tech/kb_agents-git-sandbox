package com.customer.reports;

/*
 * One counterparty group: its trades, its per-currency totals and its
 * ordering key (architecture document section 6.3 group schema).
 *
 * Group key is the counterparty business unit (external_bunit), a stable id
 * (decision D6), so a party rename never splits or merges groups. The name is
 * display-only.
 */
class Group {

    /* ab_tran.external_bunit - the group key */
    int bunit;

    /* party short_name / long_name - display name */
    String name;

    /* number of the group's selected trades (functional document 4, rule 6) */
    int tradeCount;

    /* one entry per currency present, ordered by currency code */
    CurrencyTotal[] totals;

    /* the group's largest per-currency total - its "total outstanding" for ordering (functional document 4, rule 7) */
    double orderKey;

    /* the group's trades, ordered by settle date ascending, then trade id (functional document 4, rule 8) */
    TradeRow[] trades;
}
