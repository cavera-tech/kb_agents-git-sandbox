package com.customer.reports;

/*
 * One per-currency total within a counterparty group (architecture document
 * section 6.3 group schema).
 *
 * Functional document section 4, rule 7 (totaling rule): outstanding amounts
 * are summed within a single currency only, never across currencies; within
 * one currency, opposite-side positions offset (the total is the arithmetic
 * sum of the outstanding amounts recorded for the group's trades).
 */
class CurrencyTotal {

    /* currency.name - the three-letter code this total is expressed in */
    String currency;

    /* arithmetic sum of the group's outstanding amounts in this currency */
    double amount;
}
