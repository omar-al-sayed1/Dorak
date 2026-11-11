/**
 * Calls to the Spring Boot API (same origin, the session cookie). Changes carry the session's CSRF
 * token, as the old pages' forms do. A 401 means the session ended: the page reloads, and Spring
 * sends it to log in and back here.
 */

export class HttpError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export interface Csrf {
  headerName: string;
  token: string;
}

let csrf: Csrf | null = null;

/** The session's CSRF token, from /api/session; sent with every change. */
export function setCsrf(token: Csrf): void {
  csrf = token;
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body !== undefined && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const method = (init.method ?? "GET").toUpperCase();
  if (method !== "GET" && csrf) {
    headers.set(csrf.headerName, csrf.token);
  }
  const response = await fetch(path, { ...init, headers, credentials: "same-origin" });
  if (response.status === 401) {
    signIn();
    throw new HttpError(401, "Not logged in");
  }
  if (!response.ok) {
    throw new HttpError(response.status, await response.text());
  }
  if (response.status === 204 || response.headers.get("Content-Length") === "0") {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/** Ends the session (Spring's logout, with the CSRF token) and opens the login page. */
export async function logOut(): Promise<void> {
  await fetch("/logout", {
    method: "POST",
    headers: csrf ? { [csrf.headerName]: csrf.token } : {},
    credentials: "same-origin",
    redirect: "manual",
  });
  window.location.assign("/login?logout");
}

/**
 * Off to log in. Served by Spring, reloading this page does it (and comes back after); under the
 * Vite dev server, which doesn't check logins, the login page is opened directly.
 */
export function signIn(): void {
  window.location.assign(import.meta.env.DEV ? "/login" : window.location.pathname + window.location.search);
}
