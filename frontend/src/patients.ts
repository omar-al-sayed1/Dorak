import type { Translate } from "./i18n";

/** GET /api/patients: the patients table, and the Add Payment picker. */

export type Filter = "all" | "booked" | "unbooked" | "owes" | "family" | "opted-out";
export type Sort = "name" | "next" | "last" | "visits" | "balance";

export interface PatientRow {
  id: number;
  name: string;
  phone: string;
  language: "ar" | "en";
  initials: string;
  hue: number;
  optedOut: boolean;
  /** Their next booked or confirmed appointment; days from today (0 today, 1 tomorrow). */
  next: { appointmentId: number; label: string; days: number; doctor: string | null } | null;
  lastVisit: string | null;
  /** Days since the last visit (0 today). */
  lastVisitDays: number | null;
  visits: number;
  /** What they owe (negative: in credit); null for a doctor's login. */
  balance: number | null;
  /** One colour per phone number, to tie a family's rows together. */
  familyHue: number;
  /** The others on their number. */
  family: string[];
}

export interface Counts {
  all: number;
  booked: number;
  unbooked: number;
  owes: number;
  family: number;
  optedOut: number;
}

export interface PatientsPage {
  total: number;
  counts: Counts;
  patients: PatientRow[];
  nextPage: number | null;
}

export function patientsUrl(params: { q?: string; filter?: Filter; sort?: Sort; dir?: "asc" | "desc"; page?: number;
  size?: number }): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "") {
      query.set(key, String(value));
    }
  }
  return `/api/patients?${query.toString()}`;
}

/** "Today", "Tomorrow", "In 3 days", "Yesterday", "12 days ago". */
export function relativeDay(t: Translate, days: number): string {
  if (days === 0) {
    return t("today.relative.today");
  }
  if (days === 1) {
    return t("today.relative.tomorrow");
  }
  if (days === -1) {
    return t("today.relative.yesterday");
  }
  return days > 0 ? t("today.relative.ahead", days) : t("today.relative.ago", -days);
}
