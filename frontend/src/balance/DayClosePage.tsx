import { useEffect, useState } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useSearchParams } from "react-router";
import { api } from "../api";
import { useT } from "../i18n";
import { Icon } from "../icons";
import { cents, lbp, METHODS, parseAmount, usd, type PaymentMethod } from "../money";
import { PageSkeleton } from "../skeletons";

interface DayData {
  date: string;
  dateLabel: string;
  today: boolean;
  previousDate: string;
  nextDate: string;
  payments: Array<{ id: number; time: string; patient: string; amountUsd: number; method: PaymentMethod;
    amountLbp: number | null; receiptUrl: string }>;
  byMethod: Record<PaymentMethod, number>;
  total: number;
  expectedCash: number;
  close: { expected: number; counted: number; difference: number; closedBy: string; closedAt: string } | null;
}

/** The day's takings by method, then the drawer: the cash it should hold against what was counted. */
export function DayClosePage() {
  const t = useT();
  const navigate = useNavigate();
  const queries = useQueryClient();
  const [params] = useSearchParams();
  const date = params.get("date");
  const day = useQuery({
    queryKey: ["money", "day", date],
    queryFn: () => api<DayData>(date ? `/api/money/day?date=${encodeURIComponent(date)}` : "/api/money/day"),
    placeholderData: keepPreviousData,
  });
  const [counted, setCounted] = useState("");
  const close = useMutation({
    mutationFn: (data: DayData) => api<DayData>("/api/money/day", {
      method: "PUT", body: JSON.stringify({ date: data.date, countedCash: parseAmount(counted) }),
    }),
    onSuccess: (data) => queries.setQueryData(["money", "day", date], data),
  });

  useEffect(() => {
    document.title = t("day.title");
  }, [t]);
  useEffect(() => {
    setCounted(day.data?.close ? String(day.data.close.counted) : "");
  }, [day.data?.date, day.data?.close]);

  if (!day.data) {
    return <PageSkeleton />;
  }
  const data = day.data;
  const countedAmount = parseAmount(counted);
  const difference = countedAmount === null ? null : cents(countedAmount - data.expectedCash);
  return (
    <main className="container">
      <Link className="back-link" to="/balance"><Icon name="chevron-left" flip /><span>{t("balance.title")}</span></Link>
      <header className="page-head">
        <div>
          <div className="eyebrow"><Icon name="receipt" /><span>{t("day.title")}</span></div>
          <h1>{data.dateLabel}</h1>
        </div>
        <nav className="segmented" aria-label={t("today.pick-date")}>
          <Link className="icon-btn" to={`/balance/day?date=${data.previousDate}`} aria-label={t("today.previous")}>
            <Icon name="chevron-left" flip />
          </Link>
          <Link to="/balance/day" className={data.today ? "current" : undefined}>{t("today.today")}</Link>
          <span className="date-input button ghost icon-btn">
            <Icon name="today" />
            <input type="date" value={data.date} aria-label={t("today.pick-date")}
                   onChange={(event) => event.target.value && navigate(`/balance/day?date=${event.target.value}`)} />
          </span>
          <Link className="icon-btn" to={`/balance/day?date=${data.nextDate}`} aria-label={t("today.next")}>
            <Icon name="chevron-right" flip />
          </Link>
        </nav>
      </header>

      <div className="stats">
        {METHODS.map((method) => (
          <div key={method} className={method === "CASH" ? "stat tone-ok" : "stat"}>
            <div className="stat-head"><span className="stat-label">{t(`method.${method}`)}</span></div>
            <div className="stat-value" dir="ltr">{usd(data.byMethod[method] ?? 0)}</div>
          </div>
        ))}
      </div>

      <div className="balance-grid">
        <section className="card">
          <div className="section-head">
            <h2>{t("day.takings")}</h2>
            <span className="muted">{t("day.total", usd(data.total))}</span>
          </div>
          {data.payments.length === 0 ? <p className="empty-note"><Icon name="receipt" /><span>{t("day.none")}</span></p> : (
            <ul className="owes-list">
              {data.payments.map((payment) => (
                <li key={payment.id}>
                  <span className="day-time" dir="ltr">{payment.time}</span>
                  <span className="grow"><strong>{payment.patient}</strong>
                    <span className="muted small">{t(`method.${payment.method}`)}
                      {payment.amountLbp !== null && <> · <span dir="ltr">{lbp(payment.amountLbp)}</span></>}</span>
                  </span>
                  <strong dir="ltr">{usd(payment.amountUsd)}</strong>
                  <a className="button ghost sm icon-btn" href={payment.receiptUrl} target="_blank" rel="noopener"
                     aria-label={t("pay.receipt")}><Icon name="receipt" /></a>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="card stack drawer-card">
          <div className="section-head"><h2>{t("day.title")}</h2></div>
          <p className="drawer-expected"><span>{t("day.expected-cash")}</span><strong dir="ltr">{usd(data.expectedCash)}</strong></p>
          <form className="stack" onSubmit={(event) => { event.preventDefault(); close.mutate(data); }}>
            <label>
              <span>{t("day.counted")}</span>
              <span className="field-icon money-field">
                <span className="currency-mark" aria-hidden="true">$</span>
                <input inputMode="decimal" value={counted} onChange={(event) => setCounted(event.target.value)} dir="ltr" />
              </span>
            </label>
            {difference !== null && (
              <p className={difference === 0 ? "notice" : "alert"} role="status">
                <Icon name={difference === 0 ? "check-circle" : "alert"} />
                <span>{difference === 0 ? t("day.match") : difference < 0 ? t("day.short", usd(-difference)) : t("day.over", usd(difference))}</span>
              </p>
            )}
            <div className="form-actions">
              <button type="submit" className={close.isPending ? "primary is-busy" : "primary"}
                      disabled={countedAmount === null || close.isPending}>
                <Icon name="check" /><span>{t(data.close ? "day.reclose" : "day.close")}</span>
              </button>
            </div>
          </form>
          {data.close && <p className="muted small">{t("day.closed", data.close.closedBy, data.close.closedAt)}</p>}
        </section>
      </div>
    </main>
  );
}
