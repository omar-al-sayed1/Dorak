import { useEffect, useRef, useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "./api";
import { useT } from "./i18n";
import { usd } from "./money";
import { relativeDay, type PatientRow } from "./patients";

/**
 * A patient's name that shows their card on hover or keyboard focus: next appointment, last visit,
 * visits, balance (the front desk's) and family, without leaving the page. Fetched when first shown.
 */
export function PatientName({ id, children }: { id: number | null; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const timer = useRef(0);
  useEffect(() => () => clearTimeout(timer.current), []);
  if (id === null) {
    return <>{children}</>;
  }
  const show = () => {
    clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setOpen(true), 350);
  };
  const hide = () => {
    clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setOpen(false), 150);
  };
  return (
    <span className="pcard-wrap" onMouseEnter={show} onMouseLeave={hide} onFocus={show} onBlur={hide}>
      <span tabIndex={0} className="pcard-name">{children}</span>
      {open && <PatientCard id={id} />}
    </span>
  );
}

function PatientCard({ id }: { id: number }) {
  const t = useT();
  const card = useQuery({
    queryKey: ["patient-card", id],
    queryFn: () => api<PatientRow>(`/api/patients/${id}/card`),
    staleTime: 60_000,
  });
  const patient = card.data;
  return (
    <span className="pcard" role="tooltip">
      {!patient ? <span className="pcard-loading" aria-busy="true" /> : (
        <>
          <span className="pcard-head">
            <span className="avatar sm" style={{ ["--hue" as string]: patient.hue }}>{patient.initials}</span>
            <span><strong>{patient.name}</strong><span className="muted small" dir="ltr">{patient.phone}</span></span>
          </span>
          <dl className="pcard-facts">
            <dt>{t("patients.next")}</dt>
            <dd>{patient.next ? <>{relativeDay(t, patient.next.days)} · {patient.next.label}</> : "—"}</dd>
            <dt>{t("patients.col.last")}</dt>
            <dd>{patient.lastVisit ?? t("patients.never")}</dd>
            <dt>{t("patients.col.visits")}</dt>
            <dd>{patient.visits}</dd>
            {patient.balance !== null && (
              <>
                <dt>{t("patients.col.balance")}</dt>
                <dd className={patient.balance > 0 ? "owing" : undefined} dir="ltr">
                  {patient.balance === 0 ? "—" : patient.balance > 0 ? usd(patient.balance) : `+${usd(-patient.balance)}`}
                </dd>
              </>
            )}
          </dl>
          {patient.family.length > 0 && <span className="pcard-family">{t("patients.family", patient.family.join(", "))}</span>}
        </>
      )}
    </span>
  );
}
