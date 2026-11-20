import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router";
import { App } from "../App";
import { SessionGate } from "../session";

const session = {
  username: "secretary", role: "SECRETARY", clinicId: 1, clinicName: "Dr. Demo", doctorId: null, language: "en",
  initials: "S", hue: 120, attentionCount: 0, csrf: { headerName: "X-CSRF-TOKEN", token: "t" },
};

const messages: Record<string, string> = {
  "balance.title": "Balance", "balance.owes": "Who owes", "balance.owes.none": "Nobody owes anything.",
  "balance.owes.days": "{0} days", "balance.owes.since": "since {0}", "balance.tile.collected": "Collected in {0}",
  "balance.tile.owed": "Owed in all", "balance.tile.owed.sub": "Patients who owe: {0}", "balance.tile.oldest": "Waiting longest",
  "balance.tile.oldest.none": "Nothing overdue.", "balance.vs-last": "{0} vs. {1}", "attention.call": "Call",
};

const owing = (patientId: number, name: string, balance: number, days: number) =>
  ({ patientId, name, phone: `+9617012345${patientId}`, initials: "X", hue: 10, balance, since: "Mon 5 Oct", days });

function serve(owes: unknown, month: unknown) {
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    const path = url.split("?")[0];
    const body = path === "/api/session" ? session : path === "/api/messages" ? messages
      : path === "/api/money/owes" ? owes : path === "/api/money/month" ? month : null;
    return body === null ? new Response(null, { status: 404 }) : new Response(JSON.stringify(body), { status: 200 });
  }));
}

function renderBalance() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={["/balance"]}>
        <SessionGate><App /></SessionGate>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("Balance", () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("shows the month beside last month, what is owed, and each debt against the biggest", async () => {
    serve({ total: 120, patients: [owing(1, "Rana Haddad", 80, 45), owing(2, "Sami Khoury", 40, 3)] },
      { month: "October", collected: 300, lastMonth: "September", lastCollected: 200 });
    renderBalance();

    const collected = (await screen.findByText("Collected in October")).closest("section") as HTMLElement;
    expect(within(collected).getAllByText("$300.00")).toHaveLength(2);
    expect(within(collected).getByText("+50% vs. September")).toBeTruthy();

    const owed = screen.getByText("Owed in all").closest("section") as HTMLElement;
    expect(within(owed).getByText("$120.00")).toBeTruthy();
    expect(within(owed).getByText("Patients who owe: 2")).toBeTruthy();

    const oldest = screen.getByText("Waiting longest").closest("section") as HTMLElement;
    expect(within(oldest).getByText("45 days")).toBeTruthy();
    expect(within(oldest).getByText("Rana Haddad")).toBeTruthy();

    const rows = screen.getAllByRole("listitem");
    expect(rows).toHaveLength(2);
    expect(rows[0].style.getPropertyValue("--share")).toBe("1");
    expect(rows[0].className).toBe("old");
    expect(rows[1].style.getPropertyValue("--share")).toBe("0.5");
    expect(rows[1].className).toBe("");
  });

  it("says so when nobody owes, and compares nothing with an empty last month", async () => {
    serve({ total: 0, patients: [] }, { month: "October", collected: 50, lastMonth: "September", lastCollected: 0 });
    renderBalance();

    expect(await screen.findByText("Nobody owes anything.")).toBeTruthy();
    expect(await screen.findByText("Collected in October")).toBeTruthy();
    expect(screen.queryByText(/vs\. September/)).toBeNull();
    expect(screen.getByText("Nothing overdue.")).toBeTruthy();
  });
});
