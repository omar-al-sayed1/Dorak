/**
 * Balance (SPEC section 5.6) as the API sends it: amounts in US dollars (JSON numbers, to the cent),
 * dates already formatted in the clinic's zone and the user's language.
 */

export type MoneyStatus = "NONE" | "FREE" | "UNPAID" | "PARTLY_PAID" | "PAID";
export type PaymentMethod = "CASH" | "WHISH" | "OMT" | "CARD" | "BANK";
export type Currency = "USD" | "LBP";

export const METHODS: PaymentMethod[] = ["CASH", "WHISH", "OMT", "CARD", "BANK"];

/** Lebanese pounds to the dollar: fixed, as Money.LBP_PER_USD on the server. */
export const LBP_PER_USD = 89_500;

/** A visit's money, as Today lists it. */
export interface VisitMoney {
  appointmentId: number;
  /** Null: not charged yet. */
  charged: number | null;
  paid: number;
  due: number;
  status: MoneyStatus;
}

export interface PaymentLine {
  id: number;
  amountUsd: number;
  method: PaymentMethod;
  amountLbp: number | null;
  paidAt: string;
  receiptUrl: string;
}

/** GET /api/money/appointments/{id}: what the payment panel shows. */
export interface Visit {
  appointmentId: number;
  patientId: number;
  patientName: string;
  doctor: string;
  when: string;
  status: MoneyStatus;
  charged: number | null;
  paid: number;
  due: number;
  patientBalance: number;
  familyBalance: number;
  familySize: number;
  payments: PaymentLine[];
}

export interface Recorded {
  visit: Visit;
  receiptUrl: string | null;
}

/** "$1,250.00". Western digits in both languages, as everywhere in the dashboard. */
export function usd(amount: number): string {
  return "$" + amount.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** "1,800,000 LBP". */
export function lbp(amount: number): string {
  return amount.toLocaleString("en-US", { maximumFractionDigits: 0 }) + " LBP";
}

/** To the cent, without floating-point leftovers (0.1 + 0.2). */
export function cents(amount: number): number {
  return Math.round(amount * 100) / 100;
}

/** Dollars in lira at the rate, to the whole lira. */
export function toLbp(dollars: number, lbpPerUsd: number): number {
  return Math.round(dollars * lbpPerUsd);
}

/** Lira in dollars at the rate, to the cent: what the server will record. */
export function fromLbp(lira: number, lbpPerUsd: number): number {
  return cents(lira / lbpPerUsd);
}

/** A typed amount ("30", "30.5", "1,800,000"), or null if it isn't one. */
export function parseAmount(text: string): number | null {
  const clean = text.replace(/[,\s]/g, "");
  if (!/^\d+(\.\d+)?$/.test(clean)) {
    return null;
  }
  return Number(clean);
}
