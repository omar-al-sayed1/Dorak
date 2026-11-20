import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router";
import { App } from "../App";
import { SessionGate } from "../session";
import type { Today } from "./TodayPage";

const session = {
  username: "secretary", role: "SECRETARY", clinicId: 1, clinicName: "Dr. Demo", doctorId: null, language: "en",
  initials: "S", hue: 120, attentionCount: 2, csrf: { headerName: "X-CSRF-TOKEN", token: "t" },
};

const messages: Record<string, string> = {
  "app.name": "Dorak", "nav.today": "Today", "today.title": "Today", "today.relative.today": "Today",
  "today.stats.total": "Appointments", "today.stats.whatsapp": "{0} booked on WhatsApp",
  "today.doctor.count": "{0} appointments", "today.now": "Now {0}", "status.CONFIRMED": "Confirmed",
  "status.cancelled-by-patient": "Cancelled by the patient", "source.WHATSAPP": "WhatsApp", "source.MANUAL": "Added by the clinic",
  "nav.attention": "Needs attention", "patient.deleted": "Deleted patient", "action.arrived": "Arrived",
  "action.no-show": "No-show", "action.undo": "Undo", "today.toast.arrived": "{0} marked as arrived.",
  "today.progress": "{0} of {1} arrived",
};

const today: Today = {
  date: "2026-10-14", dateLabel: "Wednesday 14 October 2026", today: true, relativeDayKey: "today.relative.today",
  relativeDayCount: "0", previousDate: "2026-10-13", nextDate: "2026-10-15", nowTime: "12:00 PM",
  stats: { total: 1, confirmed: 1, confirmedPercent: 100, arrived: 0, noShows: 0, cancelled: 1, cancelledByPatient: 1, whatsApp: 1 },
  doctors: [{
    id: 7, name: "Dr. Khoury", initials: "K", hue: 40, active: 1, nowIndex: 1,
    rows: [
      { id: 1, time: "10:30 AM", patientName: "Rana Haddad", patientPhone: "+96170123456", initials: "RH", hue: 10,
        status: "CONFIRMED", source: "WHATSAPP", cancelled: false, cancelledByPatient: false, past: true,
        canArrive: true, canNoShow: true, canCancel: false, canReschedule: false, money: null, patientId: 3 },
      { id: 2, time: "1:00 PM", patientName: null, patientPhone: null, initials: "?", hue: 210,
        status: "CANCELLED", source: "MANUAL", cancelled: true, cancelledByPatient: true, past: false,
        canArrive: false, canNoShow: false, canCancel: false, canReschedule: false, money: null, patientId: null },
    ],
  }],
};

function serve(routes: Record<string, unknown>) {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") {
      return new Response(null, { status: 204 });
    }
    const path = url.split("?")[0];
    return path in routes ? new Response(JSON.stringify(routes[path]), { status: 200 }) : new Response(null, { status: 404 });
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

function renderApp() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <SessionGate><App /></SessionGate>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("Today", () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("shows the day's appointments per doctor, with the now line and the clinic's menu", async () => {
    serve({ "/api/session": session, "/api/messages": messages, "/api/today": today });

    renderApp();

    expect(await screen.findByRole("heading", { name: "Wednesday 14 October 2026" })).toBeTruthy();
    expect(screen.getByText("Dr. Khoury")).toBeTruthy();
    expect(screen.getByText("Rana Haddad")).toBeTruthy();
    expect(screen.getByText("Deleted patient")).toBeTruthy();
    expect(screen.getByText("Cancelled by the patient")).toBeTruthy();
    expect(screen.getByText("Now 12:00 PM")).toBeTruthy();
    expect(screen.getByText("1 booked on WhatsApp")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Needs attention (2)" }).getAttribute("href")).toBe("/attention");
    expect(document.documentElement.dir).toBe("ltr");
  });

  it("marks a patient arrived, with Undo in the toast", async () => {
    const fetch = serve({ "/api/session": session, "/api/messages": messages, "/api/today": today });

    renderApp();

    expect(await screen.findByText(/^0 of 1 arrived/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Arrived" }));
    expect(await screen.findByText("Rana Haddad marked as arrived.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Undo" }));

    await waitFor(() => expect(fetch.mock.calls.filter(([, init]) => init?.method === "POST").map(([url]) => url))
      .toEqual(["/api/appointments/1/arrived", "/api/appointments/1/unmark"]));
  });

  it("is right to left in Arabic", async () => {
    serve({ "/api/session": { ...session, language: "ar" }, "/api/messages": messages, "/api/today": today });

    renderApp();

    await screen.findByText("Dr. Khoury");
    expect(document.documentElement.dir).toBe("rtl");
    expect(document.documentElement.lang).toBe("ar");
  });
});
