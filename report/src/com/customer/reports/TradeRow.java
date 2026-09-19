package com.customer.reports;

/*
 * One selected trade, carried from C2 (selection) through C3 (aggregation) to
 * C4 (presentation) of the report in docs/architecture/issue-1.md.
 *
 * Deliberately a plain value object with no platform (com.olf.openjvs) types,
 * so the C3 grouping / totaling / ordering rules can be unit tested without a
 * live Endur/Findur session (architecture document, section 9).
 *
 * Fields follow the architecture document section 6.2 trade-row schema; that
 * section's "Source" column maps each field to its ab_tran / party / currency
 * column, and the functional document section 3 to the knowledge base.
 */
class TradeRow {

    /* ab_tran.tran_num - "A unique ID number assigned to the transaction" - the trade identifier */
    int tranNum;

    /* ab_tran.deal_tracking_num - deal-level tracking number, for reference (optional per 6.2) */
    int dealTrackingNum;

    /* ab_tran.external_bunit - "The external business unit (or counterparty)" - the group key (D6) */
    int bunit;

    /* party.short_name, fallback party.long_name (A1) - the counterparty's display name */
    String counterparty;

    /* ab_tran.ins_num - "A unique ID assigned to each instrument" */
    int insNum;

    /* ab_tran.settle_date - "The date the deal settles" - Julian business date; C2 guarantees present and strictly before the business date */
    int settleDate;

    /* ab_tran.position - outstanding amount as stored; zero included (functional document 4, rule Q4) */
    double position;

    /* ab_tran.unit - "The unit or measure used in the deal" */
    String unit;

    /* currency.name - "the currency's three-letter code" (KB: ADMIN Static Data -> Currencies) */
    String currency;

    /* business date - settle date, whole calendar days (C3, decision D5) */
    int daysOverdue;
}
