import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter } from "react-router";
// The dashboard's stylesheet, the same file the old pages use: React draws the same markup.
import "../../src/main/resources/static/css/app.css";
import { App } from "./App";
import { HttpError } from "./api";
import { SessionGate } from "./session";

const queries = new QueryClient({
  defaultOptions: {
    queries: {
      // A 401 already sent the page to log in, and a 404 won't change by asking again.
      retry: (failures, error) => !(error instanceof HttpError && error.status < 500) && failures < 2,
      refetchOnWindowFocus: true,
    },
  },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queries}>
      <BrowserRouter basename="/app">
        <SessionGate>
          <App />
        </SessionGate>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
