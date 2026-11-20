import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router";
import { api, HttpError } from "../api";
import { useT } from "../i18n";
import { Icon } from "../icons";
import { PageSkeleton } from "../skeletons";
import { cents, fromLbp, lbp, LBP_PER_USD, METHODS, parseAmount, toLbp, usd, type Currency, type PaymentMethod,
  type Recorded, type Visit } from "../money";

type Mode = "full" | "half" | "other" | "later";

/**
 * A visit's payment, in the slide-over: its fee, then Paid in full, Half, Other amount or Later;
 * how they paid; in dollars or in lira at the fixed rate.
 * After saving: the receipt link to send. Also lists the visit's payments, to delete a mistake.
 */
export function PaymentPanel({ appointmentId, onClose }: { appointmentId: number; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const t = useT();
  const visit = useQuery({
    queryKey: ["visit", appointmentId],
    queryFn: () => api<Visit>(`/api/money/appointments/${appointmentId}`),
  });

  useEffect(() => {
    const element = dialog.current;
    if (element && !element.open) {
      element.showModal();
    }
  }, []);

  return (
    <dialog ref={dialog} className="drawer" aria-label={t("pay.title")} onClose={onClose}
            onClick={(event) => event.target === dialog.current && dialog.current?.close()}>
      <div className="drawer-head">
        <button type="button" className="ghost icon-btn" onClick={() => dialog.current?.close()} aria-label={t("pay.close")}>
          <Icon name="x" />
        </button>
      </div>
      <div className="drawer-body">
        {visit.data ? <PaymentForm visit={visit.data} /> : <PageSkeleton />}
      </div>
    </dialog>
  );
}

function PaymentForm({ visit }: { visit: Visit }) {
  const t = useT();
  const queries = useQueryClient();
  const [fee, setFee] = useState(() => visit.charged === null ? "" : String(visit.charged));
  const [mode, setMode] = useState<Mode>("full");
  const [other, setOther] = useState("");
  const [currency, setCurrency] = useState<Currency>("USD");
  const [method, setMethod] = useState<PaymentMethod>("CASH");
  const [receipt, setReceipt] = useState<string | null>(null);
  const [done, setDone] = useState<"paid" | "later" | null>(null);

  const feeAmount = parseAmount(fee);
  const due = feeAmount === null ? 0 : Math.max(cents(feeAmount - visit.paid), 0);
  const rate = LBP_PER_USD;
  const lira = currency === "LBP";
  // What the patient hands over, in the chosen currency.
  const amount = mode === "full" ? (lira ? toLbp(due, rate) : due)
    : mode === "half" ? (lira ? toLbp(cents(due / 2), rate) : cents(due / 2))
      : mode === "other" ? parseAmount(other) : null;
  const inDollars = amount === null ? null : lira ? fromLbp(amount, rate) : amount;

  const save = useMutation({
    mutationFn: () => api<Recorded>(`/api/money/appointments/${visit.appointmentId}`, {
      method: "POST",
      body: JSON.stringify({
        fee: feeAmount,
        payment: mode === "later" ? null : { amount, currency, method },
      }),
    }),
    onSuccess: (recorded) => {
      queries.setQueryData(["visit", visit.appointmentId], recorded.visit);
      void queries.invalidateQueries({ queryKey: ["today"] });
      void queries.invalidateQueries({ queryKey: ["money"] });
      setReceipt(recorded.receiptUrl);
      setDone(mode === "later" ? "later" : "paid");
      setMode("full");
      setOther("");
    },
  });

  const remove = useMutation({
    mutationFn: (paymentId: number) => api<void>(`/api/money/payments/${paymentId}`, { method: "DELETE" }),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: ["visit", visit.appointmentId] });
      void queries.invalidateQueries({ queryKey: ["today"] });
      void queries.invalidateQueries({ queryKey: ["money"] });
      setReceipt(null);
      setDone(null);
    },
  });

  const valid = feeAmount !== null && (mode === "later" || (inDollars !== null && inDollars > 0));
  const error = save.error instanceof HttpError ? errorKey(save.error) : null;

  return (
    <div className="pay">
      <div className="eyebrow"><Icon name="wallet" /><span>{t("pay.title")}</span></div>
      <h1>{visit.patientName}</h1>
      <p className="muted">{visit.doctor} · {visit.when}</p>

      <div className="pay-balances">
        <span className={visit.patientBalance > 0 ? "pay-badge owing" : "pay-badge"}>{t("pay.balance", usd(visit.patientBalance))}</span>
        {visit.familySize > 1 && <span className="pay-badge">{t("pay.family-balance", usd(visit.familyBalance))}</span>}
        <Link className="button ghost sm" to={`/patients/${visit.patientId}`}><Icon name="user" /><span>{t("pay.see-account")}</span></Link>
      </div>

      {done && (
        <div className="notice pay-done" role="status">
          <Icon name="check-circle" />
          <div>
            <strong>{t(done === "paid" ? "pay.recorded" : "pay.fee-saved")}</strong>
            {receipt && <ReceiptLink url={receipt} />}
          </div>
        </div>
      )}

      <section className="form-section stack">
        <label>
          <span>{t("pay.fee")}</span>
          <span className="field-icon money-field">
            <span className="currency-mark" aria-hidden="true">$</span>
            <input inputMode="decimal" value={fee} onChange={(event) => setFee(event.target.value)} dir="ltr" />
          </span>
          <span className="hint">{t("pay.fee.hint")}</span>
        </label>
        <p className="pay-due">
          <span>{t("pay.paid-so-far", usd(visit.paid))}</span>
          <strong>{t("pay.due", usd(due))}</strong>
        </p>
      </section>

      <section className="form-section stack">
        <div className="pay-modes" role="radiogroup" aria-label={t("pay.amount")}>
          {(["full", "half", "other", "later"] as const).map((choice) => (
            <button key={choice} type="button" role="radio" aria-checked={mode === choice}
                    className={mode === choice ? "pay-mode on" : "pay-mode"} onClick={() => setMode(choice)}
                    disabled={choice !== "other" && choice !== "later" && due === 0}>
              <Icon name={{ full: "check-circle", half: "chart", other: "pencil", later: "clock" }[choice]} />
              <span>{t(`pay.${choice}`)}</span>
            </button>
          ))}
        </div>

        {mode !== "later" && (
          <>
            <div className="stack tight">
              <span className="field-label">{t("pay.in")}</span>
              <div className="radio-pills" role="radiogroup" aria-label={t("pay.in")}>
                {(["USD", "LBP"] as const).map((choice) => (
                  <label key={choice}>
                    <input type="radio" name="currency" checked={currency === choice} onChange={() => setCurrency(choice)} />
                    <span>{choice === "USD" ? "$ USD" : "ل.ل. LBP"}</span>
                  </label>
                ))}
              </div>
            </div>

            {mode === "other" ? (
              <label>
                <span>{t("pay.amount")}</span>
                <span className={lira ? "field-icon money-field wide" : "field-icon money-field"}>
                  <span className="currency-mark" aria-hidden="true">{lira ? "ل.ل." : "$"}</span>
                  <input inputMode="decimal" value={other} onChange={(event) => setOther(event.target.value)} dir="ltr" autoFocus />
                </span>
              </label>
            ) : (
              amount !== null && <p className="pay-amount" dir="ltr">{lira ? lbp(amount) : usd(amount)}</p>
            )}
            {lira && inDollars !== null && (
              <p className="hint">{t("pay.equals", usd(inDollars), rate.toLocaleString("en-US"))}</p>
            )}

            <div className="stack tight">
              <span className="field-label">{t("pay.method")}</span>
              <div className="pay-methods">
                {METHODS.map((choice) => (
                  <button key={choice} type="button" aria-pressed={method === choice}
                          className={method === choice ? "pay-method on" : "pay-method"} onClick={() => setMethod(choice)}>
                    {t(`method.${choice}`)}
                  </button>
                ))}
              </div>
            </div>
          </>
        )}

        {error && <p className="alert" role="alert"><Icon name="alert" /><span>{t(error)}</span></p>}
        <div className="form-actions">
          <button type="button" className={save.isPending ? "primary is-busy" : "primary"} disabled={!valid || save.isPending}
                  onClick={() => save.mutate()}>
            <Icon name={mode === "later" ? "clock" : "check"} />
            <span>{t(mode === "later" ? "pay.save-later" : "pay.save")}</span>
          </button>
        </div>
      </section>

      {visit.payments.length > 0 && (
        <section className="form-section">
          <h2>{t("pay.history")}</h2>
          <ul className="pay-history">
            {visit.payments.map((payment) => (
              <li key={payment.id}>
                <span className="pay-history-amount" dir="ltr">{usd(payment.amountUsd)}</span>
                <span className="muted small">
                  {t(`method.${payment.method}`)}{payment.amountLbp !== null && <> · <span dir="ltr">{lbp(payment.amountLbp)}</span></>}
                  {" · "}{payment.paidAt}
                </span>
                <a className="button ghost sm icon-btn" href={payment.receiptUrl} target="_blank" rel="noopener"
                   title={t("pay.receipt")} aria-label={t("pay.receipt")}><Icon name="receipt" /></a>
                <button type="button" className="sm danger icon-btn" title={t("pay.delete")} aria-label={t("pay.delete")}
                        disabled={remove.isPending}
                        onClick={() => window.confirm(t("pay.delete-confirm")) && remove.mutate(payment.id)}>
                  <Icon name="trash" />
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

/** The receipt link, to open or to copy and send on WhatsApp. */
export function ReceiptLink({ url }: { url: string }) {
  const t = useT();
  const [copied, setCopied] = useState(false);
  return (
    <span className="receipt-link">
      <a href={url} target="_blank" rel="noopener"><Icon name="receipt" /><span>{t("pay.receipt")}</span></a>
      <button type="button" className="sm ghost" onClick={() => {
        void navigator.clipboard?.writeText(url).then(() => {
          setCopied(true);
          setTimeout(() => setCopied(false), 2000);
        });
      }}>
        <span>{t(copied ? "pay.copied" : "pay.copy-receipt")}</span>
      </button>
    </span>
  );
}

/** The text key of an API error ({"error": "error.amount"}), or a general one. */
function errorKey(error: HttpError): string {
  try {
    const body = JSON.parse(error.message) as { error?: string };
    return body.error ?? "error.not-allowed";
  } catch {
    return "error.not-allowed";
  }
}
