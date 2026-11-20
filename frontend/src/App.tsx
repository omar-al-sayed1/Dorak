import { useEffect } from "react";
import { Route, Routes } from "react-router";
import { BalancePage } from "./balance/BalancePage";
import { DayClosePage } from "./balance/DayClosePage";
import { PatientAccountPage } from "./balance/PatientAccountPage";
import { PatientsPage } from "./pages/PatientsPage";
import { TodayPage } from "./pages/TodayPage";
import { useSession } from "./session";
import { Shell } from "./Shell";
import { PageSkeleton } from "./skeletons";
import { ToastProvider } from "./toasts";

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
    <ToastProvider>
      <Shell>
        <Routes>
          <Route path="/" element={<TodayPage />} />
          <Route path="/patients" element={<PatientsPage />} />
          {session.doctorId === null && (
            <>
              <Route path="/balance" element={<BalancePage />} />
              <Route path="/balance/day" element={<DayClosePage />} />
              <Route path="/patients/:id" element={<PatientAccountPage />} />
            </>
          )}
          <Route path="*" element={<NotFound />} />
        </Routes>
      </Shell>
    </ToastProvider>
  );
}

function NotFound() {
  useEffect(() => {
    window.location.replace("/app/");
  }, []);
  return <PageSkeleton />;
}
