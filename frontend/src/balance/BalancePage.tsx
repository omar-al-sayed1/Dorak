import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { AddMoneyDialog } from "./AddMoneyDialog";
import { CountUp } from "../CountUp";
import { PatientName } from "../PatientCard";
import { api } from "../api";
import { useT } from "../i18n";
import { Icon } from "../icons";
import { LBP_PER_USD, usd } from "../money";
import { PageSkeleton } from "../skeletons";

interface Owing {
  patientId: number;
  name: string;
  phone: string;
  initials: string;
  hue: number;
  balance: number;
  since: string;
  days: number;
}

interface Owes {
  total: number;
  patients: Owing[];
}

interface Month {
  month: string;
  collected: number;
  lastMonth: string;
  lastCollected: number;
}

/**
 * Money at a glance: what came in this month beside last month, what is owed in all and the debt
 * waiting longest; then who owes, biggest and oldest first, each against the biggest debt.
 */
export function BalancePage() {
  const t = useT();
  const owes = useQuery({ queryKey: ["money", "owes"], queryFn: () => api<Owes>("/api/money/owes") });
  const month = useQuery({ queryKey: ["money", "month"], queryFn: () => api<Month>("/api/money/month") });
  const [adding, setAdding] = useState(false);

  useEffect(() => {
    document.title = t("balance.title");
  }, [t]);

  if (!owes.data) {
    return <PageSkeleton />;
  }
  const patients = owes.data.patients;
  const biggest = Math.max(0, ...patients.map((owing) => owing.balance));
  const oldest = patients.reduce<Owing | null>((found, owing) => (found === null || owing.days > found.days ? owing : found), null);
  return (
    <main className="container">
      <header className="page-head">
        <div>
          <h1>{t("balance.title")}</h1>
          <p className="muted">
            {t("balance.intro")} <span dir="ltr">{t("balance.rate.fixed", LBP_PER_USD.toLocaleString("en-US"))}</span>
          </p>
        </div>
        <div className="head-actions">
          <button type="button" onClick={() => setAdding(true)}><Icon name="wallet" /><span>{t("money.add")}</span></button>
          <Link className="button primary" to="/balance/day"><Icon name="receipt" /><span>{t("balance.close-day")}</span></Link>
        </div>
      </header>

      <div className="balance-tiles">
        <MonthTile month={month.data} />
        <section className="btile owed">
          <span className="btile-label">{t("balance.tile.owed")}</span>
          <strong className="btile-value" dir="ltr"><CountUp value={owes.data.total} format={usd} /></strong>
          <span className="btile-sub">{t("balance.tile.owed.sub", patients.length)}</span>
        </section>
        <section className="btile">
          <span className="btile-label">{t("balance.tile.oldest")}</span>
          <strong className="btile-value">{oldest && oldest.days > 0 ? t("balance.owes.days", oldest.days) : "—"}</strong>
          <span className="btile-sub">{oldest && oldest.days > 0 ? oldest.name : t("balance.tile.oldest.none")}</span>
        </section>
      </div>

      <section className="card owes-card">
        <div className="section-head">
          <h2>{t("balance.owes")}</h2>
          {patients.length > 0 && <span className="count">{patients.length}</span>}
        </div>
        {patients.length === 0 ? (
          <div className="empty">
            <span className="empty-icon"><Icon name="check-circle" /></span>
            <strong>{t("balance.owes.none")}</strong>
          </div>
        ) : (
          <ol className="owes-list">
            {patients.map((owing, index) => (
              <li key={owing.patientId} className={owing.days > 30 ? "old" : undefined}
                  style={{ ["--share" as string]: biggest === 0 ? 0 : owing.balance / biggest }}>
                <span className="owes-rank" aria-hidden="true">{index + 1}</span>
                <span className="avatar" style={{ ["--hue" as string]: owing.hue }}>{owing.initials}</span>
                <span className="grow">
                  <Link to={`/patients/${owing.patientId}`}><strong><PatientName id={owing.patientId}>{owing.name}</PatientName></strong></Link>
                  <span className="muted small">
                    <span dir="ltr">{owing.phone}</span> · {t("balance.owes.since", owing.since)}
                  </span>
                </span>
                <span className={owing.days > 30 ? "owes-amount old" : "owes-amount"}>
                  <strong dir="ltr">{usd(owing.balance)}</strong>
                  {owing.days > 0 && <span className="small">{t("balance.owes.days", owing.days)}</span>}
                </span>
                <a className="button sm" href={`tel:${owing.phone}`}><Icon name="phone" /><span>{t("attention.call")}</span></a>
              </li>
            ))}
          </ol>
        )}
      </section>
      {adding && <AddMoneyDialog onClose={() => setAdding(false)} />}
    </main>
  );
}

/** Collected this month, how that compares with last month, and the two as bars. */
function MonthTile({ month }: { month: Month | undefined }) {
  const t = useT();
  if (!month) {
    return <section className="btile hero" aria-busy="true"><span className="btile-label">{t("balance.tile.collected", "…")}</span></section>;
  }
  const most = Math.max(month.collected, month.lastCollected);
  const width = (amount: number) => (most === 0 ? "0%" : `${(100 * amount) / most}%`);
  const change = month.lastCollected > 0 ? Math.round((100 * (month.collected - month.lastCollected)) / month.lastCollected) : null;
  return (
    <section className="btile hero">
      <span className="btile-label">{t("balance.tile.collected", month.month)}</span>
      <strong className="btile-value" dir="ltr"><CountUp value={month.collected} format={usd} /></strong>
      {change !== null && (
        <span className={change < 0 ? "btile-delta down" : "btile-delta"}>
          {t("balance.vs-last", `${change > 0 ? "+" : ""}${change}%`, month.lastMonth)}
        </span>
      )}
      <div className="month-bars">
        <span>{month.month}</span>
        <i className="now"><b style={{ ["--w" as string]: width(month.collected) }} /></i>
        <span dir="ltr">{usd(month.collected)}</span>
        <span>{month.lastMonth}</span>
        <i><b style={{ ["--w" as string]: width(month.lastCollected) }} /></i>
        <span dir="ltr">{usd(month.lastCollected)}</span>
      </div>
    </section>
  );
}
