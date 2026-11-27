import { useEffect, useRef, useState } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, HttpError } from "../api";
import { useT } from "../i18n";
import { Icon } from "../icons";
import { cents, fromLbp, LBP_PER_USD, METHODS, parseAmount, usd, type Currency, type PaymentMethod } from "../money";
import { patientsUrl, type PatientsPage } from "../patients";
import { ReceiptLink } from "./PaymentPanel";

/** Who the money is for: from the picker, or the row or account it was opened from. */
export interface Picked {
  id: number;
  name: string;
  phone: string;
  initials: string;
  hue: number;
  /** What they owe now (negative: in credit). */
  balance: number;
  /** Added from New patient, but that name on that number was already a patient: theirs is used. */
  existing?: boolean;
}

/** What POST /api/patients answers: the patient to put money on. */
interface Added {
  id: number;
  name: string;
  phone: string;
  initials: string;
  hue: number;
  balance: number;
  existing: boolean;
}

type Kind = "OWES" | "PAID";

interface Recorded {
  balance: number;
  receiptUrl: string | null;
}

/**
 * Add Payment: money on a patient's account that belongs to no visit. "They owe" adds to their
 * balance (an old debt); "They paid" records a payment, in dollars or lira, with its receipt. The
 * balance before and after is shown as it's typed.
 */
export function AddMoneyDialog({ patient = null, onClose }: { patient?: Picked | null; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const t = useT();
  const [picked, setPicked] = useState<Picked | null>(patient);

  useEffect(() => {
    const element = dialog.current;
    if (element && !element.open) {
      element.showModal();
    }
  }, []);

  return (
    <dialog ref={dialog} className="drawer" aria-label={t("add.title")} onClose={onClose}
            onClick={(event) => event.target === dialog.current && dialog.current?.close()}>
      <div className="drawer-head">
        <button type="button" className="ghost icon-btn" onClick={() => dialog.current?.close()} aria-label={t("pay.close")}>
          <Icon name="x" />
        </button>
      </div>
      <div className="drawer-body">
        <div className="pay">
          <div className="eyebrow"><Icon name="wallet" /><span>{t("money.add")}</span></div>
          <h1>{t("add.title")}</h1>
          {picked
            ? <MoneyForm key={picked.id} patient={picked} onChange={patient ? undefined : () => setPicked(null)} />
            : <PatientPicker onPick={setPicked} />}
        </div>
      </div>
    </dialog>
  );
}

function PatientPicker({ onPick }: { onPick: (patient: Picked) => void }) {
  const t = useT();
  const [query, setQuery] = useState("");
  const [adding, setAdding] = useState(false);
  const [search, setSearch] = useState("");
  useEffect(() => {
    const timer = setTimeout(() => setSearch(query.trim()), 200);
    return () => clearTimeout(timer);
  }, [query]);
  // Nothing typed: those who owe most come first, as they're the likeliest to pay.
  const found = useQuery({
    queryKey: ["patients", "pick", search],
    queryFn: () => api<PatientsPage>(patientsUrl(search === ""
      ? { sort: "balance", dir: "desc", size: 6 }
      : { q: search, size: 6 })),
    placeholderData: keepPreviousData,
  });
  const pick = (row: PatientsPage["patients"][number]) =>
    onPick({ id: row.id, name: row.name, phone: row.phone, initials: row.initials, hue: row.hue, balance: row.balance ?? 0 });
  const rows = found.data?.patients ?? [];

  if (adding) {
    return <NewPatientForm typed={query.trim()} onAdded={onPick} onBack={() => setAdding(false)} />;
  }

  return (
    <section className="form-section stack">
      <label>
        <span>{t("add.patient")}</span>
        <span className="field-icon">
          <Icon name="search" />
          <input type="search" value={query} autoFocus dir="auto" placeholder={t("add.search")} autoComplete="off"
                 onChange={(event) => setQuery(event.target.value)}
                 onKeyDown={(event) => {
                   if (event.key === "Enter" && rows[0]) {
                     event.preventDefault();
                     pick(rows[0]);
                   }
                 }} />
        </span>
      </label>
      {found.data && rows.length === 0 && <p className="empty-note"><Icon name="search" /><span>{t("add.none")}</span></p>}
      <ul className="pick-list" aria-busy={found.isFetching}>
        {rows.map((row) => (
          <li key={row.id}>
            <button type="button" className="pick" onClick={() => pick(row)}>
              <span className="avatar sm" style={{ ["--hue" as string]: row.hue }}>{row.initials}</span>
              <span className="grow">
                <strong>{row.name}</strong>
                <span className="muted small" dir="ltr">{row.phone}</span>
              </span>
              {row.balance !== null && row.balance !== 0 && <BalanceText balance={row.balance} />}
              <Icon name="chevron-right" flip />
            </button>
          </li>
        ))}
        {/* Not a patient yet: add them here, with what was typed as their name or number. */}
        <li>
          <button type="button" className={rows.length === 0 && found.data ? "pick pick-new first" : "pick pick-new"}
                  onClick={() => setAdding(true)}>
            <span className="avatar sm pick-new-icon"><Icon name="plus" /></span>
            <span className="grow">
              <strong>{t("add.new")}</strong>
              <span className="muted small">{query.trim() !== "" && rows.length === 0 ? t("add.new.typed", query.trim()) : t("add.new.hint")}</span>
            </span>
            <Icon name="chevron-right" flip />
          </button>
        </li>
      </ul>
    </section>
  );
}

/**
 * A new patient for money not about a visit: a name and a phone number, then straight on to the
 * amount. What was typed in the search starts the form: digits as the number, words as the name.
 * The same name on the same number is the same patient, and theirs is used.
 */
function NewPatientForm({ typed, onAdded, onBack }: {
  typed: string; onAdded: (patient: Picked) => void; onBack: () => void;
}) {
  const t = useT();
  const queries = useQueryClient();
  const number = /^[+\d\s()-]+$/.test(typed) && /\d{3}/.test(typed);
  const [name, setName] = useState(number ? "" : typed);
  const [phone, setPhone] = useState(number ? typed : "");
  const add = useMutation({
    mutationFn: () => api<Added>("/api/patients", { method: "POST", body: JSON.stringify({ name, phone }) }),
    onSuccess: (added) => {
      void queries.invalidateQueries({ queryKey: ["patients"] });
      onAdded({ id: added.id, name: added.name, phone: added.phone, initials: added.initials, hue: added.hue,
        balance: added.balance, existing: added.existing });
    },
  });
  const error = add.error instanceof HttpError ? errorKey(add.error) : null;
  const ready = name.trim() !== "" && phone.trim() !== "";

  return (
    <form className="form-section stack new-patient" onSubmit={(event) => {
      event.preventDefault();
      if (ready && !add.isPending) {
        add.mutate();
      }
    }}>
      <div className="section-head"><Icon name="user" /><h2>{t("add.new")}</h2></div>
      <label>
        <span>{t("add.new.name")}</span>
        <input value={name} onChange={(event) => setName(event.target.value)} dir="auto" autoComplete="off" maxLength={100}
               autoFocus={!number || name === ""} required />
      </label>
      <label>
        <span>{t("add.new.phone")}</span>
        <span className="field-icon">
          <Icon name="phone" />
          <input value={phone} onChange={(event) => setPhone(event.target.value)} dir="ltr" inputMode="tel" autoComplete="off"
                 placeholder="70 123 456" required />
        </span>
      </label>
      {error && <p className="alert" role="alert"><Icon name="alert" /><span>{t(error)}</span></p>}
      <div className="form-actions">
        <button type="submit" className="primary" disabled={!ready || add.isPending}>
          <Icon name="check" /><span>{t("add.new.save")}</span>
        </button>
        <button type="button" className="ghost" onClick={onBack}>{t("add.new.back")}</button>
      </div>
    </form>
  );
}

function MoneyForm({ patient, onChange }: { patient: Picked; onChange?: () => void }) {
  const t = useT();
  const queries = useQueryClient();
  const [kind, setKind] = useState<Kind>("PAID");
  const [amount, setAmount] = useState("");
  const [currency, setCurrency] = useState<Currency>("USD");
  const [method, setMethod] = useState<PaymentMethod>("CASH");
  const [balance, setBalance] = useState(patient.balance);
  const [done, setDone] = useState<{ kind: Kind; receiptUrl: string | null } | null>(null);

  const rate = LBP_PER_USD;
  const lira = kind === "PAID" && currency === "LBP";
  const typed = parseAmount(amount);
  const dollars = typed === null ? null : lira ? fromLbp(typed, rate) : typed;
  const after = dollars === null ? null : cents(kind === "OWES" ? balance + dollars : balance - dollars);

  const save = useMutation({
    mutationFn: () => api<Recorded>(`/api/money/patients/${patient.id}`, {
      method: "POST",
      body: JSON.stringify(kind === "OWES"
        ? { kind, amount: typed }
        : { kind, amount: typed, currency: lira ? "LBP" : "USD", method }),
    }),
    onSuccess: (recorded) => {
      setBalance(recorded.balance);
      setDone({ kind, receiptUrl: recorded.receiptUrl });
      setAmount("");
      void queries.invalidateQueries({ queryKey: ["money"] });
      void queries.invalidateQueries({ queryKey: ["patients"] });
    },
  });
  const error = save.error instanceof HttpError ? errorKey(save.error) : null;
  const valid = dollars !== null && dollars > 0;

  return (
    <>
      <div className="picked">
        <span className="avatar" style={{ ["--hue" as string]: patient.hue }}>{patient.initials}</span>
        <span className="grow">
          <strong>{patient.name}</strong>
          <span className="muted small" dir="ltr">{patient.phone}</span>
        </span>
        {onChange && <button type="button" className="ghost sm" onClick={onChange}>{t("add.change")}</button>}
      </div>
      {patient.existing && <p className="hint"><Icon name="info" /> {t("add.new.existing")}</p>}

      {done && (
        <div className="notice pay-done" role="status">
          <Icon name="check-circle" />
          <div>
            <strong>{t(done.kind === "OWES" ? "add.saved-owes" : "add.saved-paid")}</strong>
            {done.receiptUrl && <ReceiptLink url={done.receiptUrl} />}
          </div>
        </div>
      )}

      <div className="kind-cards" role="radiogroup" aria-label={t("add.title")}>
        {(["PAID", "OWES"] as const).map((choice) => (
          <button key={choice} type="button" role="radio" aria-checked={kind === choice}
                  className={`kind-card kind-${choice}${kind === choice ? " on" : ""}`} onClick={() => setKind(choice)}>
            <span className="kind-icon"><Icon name={choice === "PAID" ? "check-circle" : "plus"} /></span>
            <strong>{t(choice === "PAID" ? "add.paid" : "add.owes")}</strong>
            <span className="small">{t(choice === "PAID" ? "add.paid.hint" : "add.owes.hint")}</span>
          </button>
        ))}
      </div>

      <section className="form-section stack">
        {kind === "PAID" && (
          <div className="radio-pills" role="radiogroup" aria-label={t("pay.in")}>
            {(["USD", "LBP"] as const).map((choice) => (
              <label key={choice}>
                <input type="radio" name="add-currency" checked={currency === choice} onChange={() => setCurrency(choice)} />
                <span>{choice === "USD" ? "$ USD" : "ل.ل. LBP"}</span>
              </label>
            ))}
          </div>
        )}
        <label>
          <span>{t("add.amount")}</span>
          <span className={lira ? "field-icon money-field wide" : "field-icon money-field"}>
            <span className="currency-mark" aria-hidden="true">{lira ? "ل.ل." : "$"}</span>
            <input inputMode="decimal" value={amount} onChange={(event) => setAmount(event.target.value)} dir="ltr" />
          </span>
        </label>
        {lira && dollars !== null && (
          <p className="hint">{t("pay.equals", usd(dollars), rate.toLocaleString("en-US"))}</p>
        )}

        {kind === "PAID" && (
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
        )}

        <div className="balance-move" aria-live="polite">
          <span className="small muted">{t("add.balance")}</span>
          <BalanceText balance={balance} />
          {after !== null && (
            <>
              <Icon name="chevron-right" flip />
              <BalanceText balance={after} />
            </>
          )}
        </div>

        {error && <p className="alert" role="alert"><Icon name="alert" /><span>{t(error)}</span></p>}
        <div className="form-actions">
          <button type="button" className={save.isPending ? "primary is-busy" : "primary"} disabled={!valid || save.isPending}
                  onClick={() => save.mutate()}>
            <Icon name={kind === "PAID" ? "check" : "plus"} />
            <span>{t(kind === "PAID" ? "add.save-paid" : "add.save-owes")}</span>
          </button>
        </div>
      </section>
    </>
  );
}

/** "Owes $45.00", "In credit $5.00" or "Nothing owed", coloured. */
function BalanceText({ balance }: { balance: number }) {
  const t = useT();
  const state = balance > 0 ? "owing" : balance < 0 ? "credit" : "settled";
  const text = balance > 0 ? t("ledger.owes", usd(balance)) : balance < 0 ? t("ledger.credit", usd(-balance)) : t("ledger.settled");
  return <span className={`pay-badge ${state}`}>{text}</span>;
}

function errorKey(error: HttpError): string {
  try {
    const body = JSON.parse(error.message) as { error?: string };
    return body.error ?? "error.not-allowed";
  } catch {
    return "error.not-allowed";
  }
}
