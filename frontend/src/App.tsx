import { useEffect } from "react";
import { Route, Routes } from "react-router";
import { TodayPage } from "./pages/TodayPage";
import { useSession } from "./session";
import { Shell } from "./Shell";
import { PageSkeleton } from "./skeletons";

/**
 * The React dashboard's pages. Each step of the move to React adds its pages here; until then the
 * menu links to the old pages.
 */
export function App() {
  const session = useSession();
  const operator = session.clinicId === null;

  // The operator's pages aren't in React yet.
  useEffect(() => {
    if (operator) {
      window.location.replace("/operator");
    }
  }, [operator]);

  if (operator) {
    return <PageSkeleton />;
  }
  return (
    <Shell>
      <Routes>
        <Route path="/" element={<TodayPage />} />
        <Route path="*" element={<NotFound />} />
      </Routes>
    </Shell>
  );
}

function NotFound() {
  useEffect(() => {
    window.location.replace("/app/");
  }, []);
  return <PageSkeleton />;
}
