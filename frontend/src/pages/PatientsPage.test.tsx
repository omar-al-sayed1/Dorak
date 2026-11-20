import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router";
import { App } from "../App";
import type { PatientRow, PatientsPage } from "../patients";
import { SessionGate } from "../session";

const session = {
  username: "secretary", role: "SECRETARY", clinicId: 1, clinicName: "Dr. Demo", doctorId: null, language: "en",
  initials: "S", hue: 120, attentionCount: 0, csrf: { headerName: "X-CSRF-TOKEN", token: "t" },
};

const messages: Record<string, string> = {
  "patients.title": "Patients", "patients.count": "{0} patients", "patients.filter.all": "All",
  "patients.filter.booked": "Booked", "patients.filter.unbooked": "Not booked", "patients.filter.owes": "Owe money",
  "patients.filter.family": "Families", "patients.filter.opted-out": "Opted out", "patients.col.patient": "Patient",
  "patients.next": "Next appointment", "patients.col.last": "Last visit", "patients.col.visits": "Visits",
  "patients.col.balance": "Balance", "patients.visits": "{0} visits", "today.relative.tomorrow": "Tomorrow",
  "today.relative.ago": "{0} days ago", "language.ar": "Arabic", "language.en": "English", "money.add": "Add payment",
  "patients.never": "Not yet",
};

const rana: PatientRow = {
  id: 3, name: "Rana Haddad", phone: "+96170123456", language: "ar", initials: "RH", hue: 10, optedOut: false,
  next: { appointmentId: 40, label: "Thu 15 Oct, 10:30 AM", days: 1, doctor: "Dr. Saab" }, lastVisit: "Wed 7 Oct",
  lastVisitDays: 7, visits: 7, balance: 45, familyHue: 200, family: ["Sami Haddad"],
};

const page: PatientsPage = {
  total: 1, counts: { all: 4, booked: 2, unbooked: 2, owes: 1, family: 2, optedOut: 1 }, patients: [rana], nextPage: null,
};

function serve(doctorId: number | null = null) {
  const fetch = vi.fn(async (url: string) => {
    const path = url.split("?")[0];
    const body = path === "/api/session" ? { ...session, doctorId } : path === "/api/messages" ? messages
      : path === "/api/patients" ? page : null;
    return body === null ? new Response(null, { status: 404 }) : new Response(JSON.stringify(body), { status: 200 });
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

function renderAt(path: string) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <SessionGate><App /></SessionGate>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("Patients", () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("shows each patient as a row: next appointment, last visit, visits as dots, balance and family", async () => {
    serve();
    renderAt("/patients");

    const row = (await screen.findByText("Rana Haddad")).closest("tr") as HTMLElement;
    expect(row.className).toBe("has-family");
    expect(within(row).getByText("Tomorrow")).toBeTruthy();
    expect(within(row).getByText(/Thu 15 Oct, 10:30 AM · Dr. Saab/)).toBeTruthy();
    expect(within(row).getByText("7 days ago")).toBeTruthy();
    expect(within(row).getByLabelText("7 visits").querySelectorAll(".dot")).toHaveLength(5);
    expect(within(row).getByText("+2")).toBeTruthy();
    expect(within(row).getByText("$45.00")).toBeTruthy();
    expect(within(row).getByText("Sami Haddad")).toBeTruthy();
    expect(within(row).getByRole("link", { name: "Rana Haddad" }).getAttribute("href")).toBe("/patients/3");
    expect(screen.getByRole("button", { name: /Owe money/ }).textContent).toContain("1");
  });

  it("asks the server again for a filter and a sort", async () => {
    const fetch = serve();
    renderAt("/patients");

    fireEvent.click(await screen.findByRole("button", { name: /Owe money/ }));
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes("filter=owes"))).toBe(true));

    fireEvent.click(screen.getByRole("button", { name: "Balance" }));
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes("sort=balance") && url.includes("dir=desc")))
      .toBe(true));
  });

  it("a doctor's login sees neither money nor the money filters", async () => {
    serve(7);
    renderAt("/patients");

    await screen.findByText("Rana Haddad");
    expect(screen.queryByRole("button", { name: /Owe money/ })).toBeNull();
    expect(screen.queryByRole("button", { name: "Add payment" })).toBeNull();
    expect(screen.queryByRole("columnheader", { name: "Balance" })).toBeNull();
  });
});
