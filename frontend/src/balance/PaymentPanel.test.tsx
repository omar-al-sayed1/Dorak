import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router";
import { I18nProvider } from "../i18n";
import type { Visit } from "../money";
import { PaymentPanel } from "./PaymentPanel";

const messages: Record<string, string> = {
  "pay.title": "Payment", "pay.full": "Paid in full", "pay.half": "Half", "pay.other": "Other amount", "pay.later": "Later",
  "pay.save": "Record the payment", "pay.save-later": "Save the fee, pay later", "pay.due": "Left to pay: {0}",
  "pay.paid-so-far": "Paid so far: {0}", "pay.equals": "≈ {0} at {1} LBP", "pay.recorded": "Payment recorded.",
  "pay.receipt": "Receipt", "pay.fee": "Visit fee",
  "method.CASH": "Cash", "method.WHISH": "Whish", "method.OMT": "OMT", "method.CARD": "Card", "method.BANK": "Bank transfer",
};

const visit: Visit = {
  appointmentId: 5, patientId: 9, patientName: "Rana Haddad", doctor: "Dr. Khoury", when: "Wed 14 Oct, 10:30 AM",
  status: "NONE", charged: null, paid: 0, due: 0, patientBalance: 0, familyBalance: 0, familySize: 1, payments: [],
};

function serve(answer: (url: string, init?: RequestInit) => Response) {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => answer(url, init));
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

function renderPanel() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <I18nProvider messages={messages}><PaymentPanel appointmentId={5} onClose={() => {}} /></I18nProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function posted(fetch: ReturnType<typeof serve>): unknown {
  const call = fetch.mock.calls.find(([, init]) => init?.method === "POST");
  return call ? JSON.parse(String(call[1]?.body)) : undefined;
}

describe("PaymentPanel", () => {
  beforeAll(() => {
    // jsdom has <dialog> without showModal.
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

  it("takes the fee typed and records half of it in lira at the fixed rate", async () => {
    const fetch = serve((_url, init) => init?.method === "POST"
      ? new Response(JSON.stringify({ visit: { ...visit, charged: 30, paid: 15, due: 15, status: "PARTLY_PAID" },
        receiptUrl: "https://example.test/r/abc" }), { status: 200 })
      : new Response(JSON.stringify(visit), { status: 200 }));
    renderPanel();

    expect(await screen.findByText("Rana Haddad")).toBeTruthy();
    const fee = screen.getByLabelText(/^Visit fee/) as HTMLInputElement;
    expect(fee.value).toBe("");
    fireEvent.change(fee, { target: { value: "30" } });
    expect(screen.getByText("Left to pay: $30.00")).toBeTruthy();

    fireEvent.click(screen.getByRole("radio", { name: "Half" }));
    fireEvent.click(screen.getByLabelText("ل.ل. LBP"));
    expect(screen.getByText("1,342,500 LBP")).toBeTruthy();
    expect(screen.getByText("≈ $15.00 at 89,500 LBP")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "OMT" }));
    fireEvent.click(screen.getByRole("button", { name: "Record the payment" }));

    expect(await screen.findByText("Payment recorded.")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Receipt" }).getAttribute("href")).toBe("https://example.test/r/abc");
    expect(posted(fetch)).toEqual({ fee: 30, payment: { amount: 1342500, currency: "LBP", method: "OMT" } });
  });

  it("Later saves only the fee", async () => {
    const fetch = serve((_url, init) => init?.method === "POST"
      ? new Response(JSON.stringify({ visit: { ...visit, charged: 30, status: "UNPAID", due: 30 }, receiptUrl: null }),
        { status: 200 })
      : new Response(JSON.stringify(visit), { status: 200 }));
    renderPanel();

    fireEvent.change(await screen.findByLabelText(/^Visit fee/), { target: { value: "30" } });
    fireEvent.click(screen.getByRole("radio", { name: "Later" }));
    fireEvent.click(screen.getByRole("button", { name: "Save the fee, pay later" }));

    await waitFor(() => expect(posted(fetch)).toEqual({ fee: 30, payment: null }));
  });

  it("starts from what the visit was charged, if it was", async () => {
    serve(() => new Response(JSON.stringify({ ...visit, charged: 40, paid: 10, due: 30, status: "PARTLY_PAID" }),
      { status: 200 }));
    renderPanel();

    expect(((await screen.findByLabelText(/^Visit fee/)) as HTMLInputElement).value).toBe("40");
    expect(screen.getByText("Left to pay: $30.00")).toBeTruthy();
  });
});
