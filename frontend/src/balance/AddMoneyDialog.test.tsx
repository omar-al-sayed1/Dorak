import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router";
import { I18nProvider } from "../i18n";
import type { PatientsPage } from "../patients";
import { AddMoneyDialog } from "./AddMoneyDialog";

const messages: Record<string, string> = {
  "add.paid": "They paid", "add.owes": "They owe", "add.amount": "Amount", "add.save-owes": "Add to their balance",
  "add.save-paid": "Record the payment", "add.saved-owes": "Added to their balance.", "ledger.owes": "Owes {0}",
  "ledger.settled": "Nothing owed", "ledger.credit": "In credit {0}", "add.search": "Name or phone",
  "add.new": "New patient", "add.new.typed": "Add {0} as a new patient", "add.new.hint": "Not on the list? Add them here.",
  "add.new.name": "Name", "add.new.phone": "Phone", "add.new.save": "Continue", "add.new.back": "Back to the search",
  "add.none": "No patient found.", "error.phone": "That phone number does not look right.",
  "method.CASH": "Cash", "method.WHISH": "Whish", "method.OMT": "OMT", "method.CARD": "Card", "method.BANK": "Bank transfer",
};

const found: PatientsPage = {
  total: 1, counts: { all: 1, booked: 0, unbooked: 1, owes: 1, family: 0, optedOut: 0 }, nextPage: null,
  patients: [{
    id: 9, name: "Karim Saab", phone: "+96171222333", language: "en", initials: "KS", hue: 50, optedOut: false, next: null,
    lastVisit: null, lastVisitDays: null, visits: 0, balance: 20, familyHue: 4, family: [],
  }],
};

function serve() {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") {
      return new Response(JSON.stringify({ balance: 70, receiptUrl: null }), { status: 200 });
    }
    return url.startsWith("/api/patients") ? new Response(JSON.stringify(found), { status: 200 })
      : new Response(null, { status: 404 });
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

describe("AddMoneyDialog", () => {
  beforeAll(() => {
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
      this.open = true;
    };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
      this.open = false;
    };
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("finds the patient, then adds what they owe with the balance before and after", async () => {
    const fetch = serve();
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <I18nProvider messages={messages}><AddMoneyDialog onClose={() => {}} /></I18nProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    fireEvent.click(await screen.findByRole("button", { name: /Karim Saab/ }));
    fireEvent.click(screen.getByRole("radio", { name: /They owe/ }));
    fireEvent.change(screen.getByLabelText(/^Amount/), { target: { value: "50" } });

    expect(screen.getByText("Owes $20.00")).toBeTruthy();
    expect(screen.getByText("Owes $70.00")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Add to their balance" }));

    expect(await screen.findByText("Added to their balance.")).toBeTruthy();
    const post = fetch.mock.calls.find(([, init]) => init?.method === "POST");
    expect(post?.[0]).toBe("/api/money/patients/9");
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({ kind: "OWES", amount: 50 });
  });

  it("records a payment on the account, starting from the given patient", async () => {
    const fetch = serve();
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <I18nProvider messages={messages}>
            <AddMoneyDialog onClose={() => {}}
                            patient={{ id: 9, name: "Karim Saab", phone: "+96171222333", initials: "KS", hue: 50, balance: 20 }} />
          </I18nProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    fireEvent.change(screen.getByLabelText(/^Amount/), { target: { value: "20" } });
    expect(screen.getByText("Nothing owed")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Whish" }));
    fireEvent.click(screen.getByRole("button", { name: "Record the payment" }));

    await waitFor(() => expect(fetch.mock.calls.some(([, init]) => init?.method === "POST")).toBe(true));
    const post = fetch.mock.calls.find(([, init]) => init?.method === "POST");
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({ kind: "PAID", amount: 20, currency: "USD", method: "WHISH" });
  });
  it("adds a new patient when the search finds nobody, then records their payment", async () => {
    let added = false;
    const fetch = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === "/api/patients" && init?.method === "POST") {
        const body = JSON.parse(String(init.body)) as { phone: string };
        if (body.phone === "12") {
          return new Response(JSON.stringify({ error: "error.phone" }), { status: 400 });
        }
        added = true;
        return new Response(JSON.stringify({ id: 31, name: "Maya Khoury", phone: "+9613555666", initials: "MK", hue: 80,
          balance: 0, existing: false }), { status: 200 });
      }
      if (init?.method === "POST") {
        return new Response(JSON.stringify({ balance: -25, receiptUrl: "/r/abc" }), { status: 200 });
      }
      return url.startsWith("/api/patients")
        ? new Response(JSON.stringify({ ...found, total: 0, patients: [] }), { status: 200 })
        : new Response(null, { status: 404 });
    });
    vi.stubGlobal("fetch", fetch);
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <I18nProvider messages={messages}><AddMoneyDialog onClose={() => {}} /></I18nProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    fireEvent.change(screen.getByPlaceholderText("Name or phone"), { target: { value: "Maya Khoury" } });
    fireEvent.click(await screen.findByRole("button", { name: /Add Maya Khoury as a new patient/ }));
    expect((screen.getByLabelText("Name") as HTMLInputElement).value).toBe("Maya Khoury");

    fireEvent.change(screen.getByLabelText("Phone"), { target: { value: "12" } });
    fireEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect(await screen.findByText("That phone number does not look right.")).toBeTruthy();
    expect(added).toBe(false);

    fireEvent.change(screen.getByLabelText("Phone"), { target: { value: "03 555 666" } });
    fireEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect(await screen.findByText("+9613555666")).toBeTruthy();

    fireEvent.change(screen.getByLabelText(/^Amount/), { target: { value: "25" } });
    fireEvent.click(screen.getByRole("button", { name: "Record the payment" }));
    await waitFor(() => expect(fetch.mock.calls.map(([url]) => url)).toContain("/api/money/patients/31"));
  });
});
