import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router";
import { api } from "../api";
import { useT } from "../i18n";
import { Icon } from "../icons";
import { lbp, usd, type PaymentMethod } from "../money";
import { PageSkeleton } from "../skeletons";
import { AddMoneyDialog } from "./AddMoneyDialog";

interface Entry {
  type: "CHARGE" | "PAYMENT";
  date: string;
  amountUsd: number;
  visit: string | null;
  doctor: string | null;
  method: PaymentMethod | null;
  amountLbp: number | null;
  paymentId: number | null;
  receiptUrl: string | null;
}

interface Ledger {
  patientId: number;
  name: string;
  phone: string;
  initials: string;
  hue: number;
  balance: number;
  entries: Entry[];
  family: Array<{ patientId: number; name: string; initials: string; hue: number; balance: number }>;
  familyBalance: number;
}

/** A patient's account: what they owe (or have in credit), their family's on the same number, and the history. */
export function PatientAccountPage() {
  const t = useT();
  const { id } = useParams();
  const ledger = useQuery({ queryKey: ["money", "patient", id], queryFn: () => api<Ledger>(`/api/money/patients/${id}`) });
  const [adding, setAdding] = useState(false);

  useEffect(() => {
    document.title = ledger.data ? `${t("ledger.title")} · ${ledger.data.name}` : t("ledger.title");
  }, [t, ledger.data]);

  if (!ledger.data) {
    return <PageSkeleton />;
  }
  const account = ledger.data;
  return (
    <main className="container narrow">
      <Link className="back-link" to="/patients"><Icon name="chevron-left" flip /><span>{t("patients.title")}</span></Link>
      <header className="page-head">
        <div className="appt-who">
          <span className="avatar lg" style={{ ["--hue" as string]: account.hue }}>{account.initials}</span>
          <div>
            <div className="eyebrow"><Icon name="wallet" /><span>{t("ledger.title")}</span></div>
            <h1>{account.name}</h1>
            <a className="phone" href={`tel:${account.phone}`} dir="ltr"><Icon name="phone" /><span>{account.phone}</span></a>
          </div>
        </div>
        <div className="head-actions">
          <BalanceBadge balance={account.balance} big />
          <button type="button" className="primary" onClick={() => setAdding(true)}>
            <Icon name="wallet" /><span>{t("money.add")}</span>
          </button>
        </div>
      </header>

      {account.family.length > 1 && (
        <section className="card">
          <div className="section-head">
            <h2>{t("ledger.family")}</h2>
            <span className="muted">{t("ledger.family-total", usd(account.familyBalance))}</span>
          </div>
          <ul className="family-list">
            {account.family.map((member) => (
              <li key={member.patientId} className={member.patientId === account.patientId ? "current" : undefined}>
                <span className="avatar sm" style={{ ["--hue" as string]: member.hue }}>{member.initials}</span>
                <Link className="grow" to={`/patients/${member.patientId}`}>{member.name}</Link>
                <BalanceBadge balance={member.balance} />
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="card">
        <div className="section-head"><h2>{t("ledger.history")}</h2></div>
        {account.entries.length === 0 ? (
          <p className="empty-note"><Icon name="wallet" /><span>{t("ledger.empty")}</span></p>
        ) : (
          <ol className="ledger">
            {account.entries.map((entry, index) => (
              <li key={index} className={entry.type === "PAYMENT" ? "ledger-payment" : "ledger-charge"}>
                <span className="ledger-icon"><Icon name={entry.type === "PAYMENT" ? "check-circle" : "today"} /></span>
                <span className="grow">
                  <strong>{t(entry.type === "PAYMENT" ? "ledger.payment" : entry.visit === null ? "ledger.added" : "ledger.charge")}</strong>
                  <span className="muted small">
                    {entry.type === "PAYMENT"
                      ? <>{t(`method.${entry.method}`)}{entry.amountLbp !== null && <> · <span dir="ltr">{lbp(entry.amountLbp)}</span></>} · {entry.date}</>
                      : entry.visit === null ? entry.date : <>{entry.doctor}{entry.visit && <> · {entry.visit}</>}</>}
                  </span>
                </span>
                <span className="ledger-amount" dir="ltr">{entry.type === "PAYMENT" ? "−" : "+"}{usd(entry.amountUsd)}</span>
                {entry.receiptUrl && (
                  <a className="button ghost sm icon-btn" href={entry.receiptUrl} target="_blank" rel="noopener"
                     title={t("pay.receipt")} aria-label={t("pay.receipt")}><Icon name="receipt" /></a>
                )}
              </li>
            ))}
          </ol>
        )}
      </section>
      {adding && (
        <AddMoneyDialog onClose={() => setAdding(false)} patient={{ id: account.patientId, name: account.name,
          phone: account.phone, initials: account.initials, hue: account.hue, balance: account.balance }} />
      )}
    </main>
  );
}

/** "Owes $45.00", "In credit $5.00" or "Nothing owed". */
export function BalanceBadge({ balance, big = false }: { balance: number; big?: boolean }) {
  const t = useT();
  const state = balance > 0 ? "owing" : balance < 0 ? "credit" : "settled";
  const text = balance > 0 ? t("ledger.owes", usd(balance)) : balance < 0 ? t("ledger.credit", usd(-balance)) : t("ledger.settled");
  return <span className={`pay-badge ${state}${big ? " big" : ""}`}>{text}</span>;
}
