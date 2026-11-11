import { afterEach, describe, expect, it, vi } from "vitest";
import { api, HttpError, setCsrf } from "./api";

function respond(status: number, body?: unknown) {
  return vi.fn(async (_url: string, _init?: RequestInit) =>
    new Response(body === undefined ? null : JSON.stringify(body), { status }));
}

describe("api", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("sends the CSRF token with changes, not with reads", async () => {
    setCsrf({ headerName: "X-CSRF-TOKEN", token: "abc" });
    const fetch = respond(200, { ok: true });
    vi.stubGlobal("fetch", fetch);

    await api("/api/today");
    await api("/api/language", { method: "PUT", body: JSON.stringify({ language: "en" }) });

    const read = fetch.mock.calls[0]?.[1]?.headers as Headers;
    const change = fetch.mock.calls[1]?.[1]?.headers as Headers;
    expect(read.get("X-CSRF-TOKEN")).toBeNull();
    expect(change.get("X-CSRF-TOKEN")).toBe("abc");
    expect(change.get("Content-Type")).toBe("application/json");
  });

  it("goes to log in when the session ended", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign, pathname: "/app/", search: "" });
    vi.stubGlobal("fetch", respond(401));

    await expect(api("/api/today")).rejects.toBeInstanceOf(HttpError);
    expect(assign).toHaveBeenCalled();
  });

  it("reports other errors with their status", async () => {
    vi.stubGlobal("fetch", respond(409, { error: "error.not-allowed" }));

    await expect(api("/api/today")).rejects.toMatchObject({ status: 409 });
  });
});
