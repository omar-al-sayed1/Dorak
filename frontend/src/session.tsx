import { createContext, useContext, useEffect, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, setCsrf, type Csrf } from "./api";
import { I18nProvider, type Messages } from "./i18n";
import { PageSkeleton } from "./skeletons";

/** Who is logged in (GET /api/session). */
export interface Session {
  username: string;
  role: "OPERATOR" | "OWNER" | "SECRETARY" | "DOCTOR";
  /** Null for the operator. */
  clinicId: number | null;
  clinicName: string | null;
  /** A doctor's own login: everything is limited to this doctor. */
  doctorId: number | null;
  language: "ar" | "en";
  initials: string;
  hue: number;
  /** Null where there is no Needs attention: operators and doctors. */
  attentionCount: number | null;
  /** The clinic's payment for the service is near or late (front desk); the owner's bar links to Billing. */
  billing?: { status: "due-soon" | "overdue" | "paused"; dueOn: string; days: number; owner: boolean } | null;
  csrf: Csrf;
}

const SessionContext = createContext<Session | null>(null);

export function useSession(): Session {
  const session = useContext(SessionContext);
  if (!session) {
    throw new Error("useSession outside <SessionGate>");
  }
  return session;
}

/**
 * Loads the session and its language's texts before showing anything (a skeleton meanwhile), and
 * sets the page's language and direction: Arabic is right to left.
 */
export function SessionGate({ children }: { children: ReactNode }) {
  const session = useQuery({ queryKey: ["session"], queryFn: () => api<Session>("/api/session") });
  const language = session.data?.language;
  const messages = useQuery({
    queryKey: ["messages", language],
    queryFn: () => api<Messages>(`/api/messages?lang=${language}`),
    enabled: language !== undefined,
    staleTime: Infinity,
  });

  useEffect(() => {
    if (session.data) {
      setCsrf(session.data.csrf);
      document.documentElement.lang = session.data.language;
      document.documentElement.dir = session.data.language === "ar" ? "rtl" : "ltr";
    }
  }, [session.data]);

  if (!session.data || !messages.data) {
    return <PageSkeleton />;
  }
  return (
    <SessionContext.Provider value={session.data}>
      <I18nProvider messages={messages.data}>{children}</I18nProvider>
    </SessionContext.Provider>
  );
}

/** Switches the dashboard to the other language, for this session and as the user's own. */
export function useLanguageSwitch() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (language: "ar" | "en") =>
      api<void>("/api/language", { method: "PUT", body: JSON.stringify({ language }) }),
    onSuccess: () => client.invalidateQueries(),
  });
}
