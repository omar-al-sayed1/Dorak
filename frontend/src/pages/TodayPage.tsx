import { useEffect, useRef, useState } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useSearchParams } from "react-router";
import { api, HttpError } from "../api";
import { AddMoneyDialog } from "../balance/AddMoneyDialog";
import { PaymentPanel } from "../balance/PaymentPanel";
import { CountUp } from "../CountUp";
import { PatientName } from "../PatientCard";
import { usd, type VisitMoney } from "../money";
import { useT } from "../i18n";
import { Icon } from "../icons";
import { useSession } from "../session";
import { PageSkeleton } from "../skeletons";
import { useToast } from "../toasts";

/** GET /api/today: one day, as the old Today page shows it. */
export interface Today {
  date: string;
  dateLabel: string;
  today: boolean;
  relativeDayKey: string;
  relativeDayCount: string;
  previousDate: string;
  nextDate: string;
  nowTime: string;
  stats: Stats;
  doctors: DoctorDay[];
}

export interface Stats {
  total: number;
  confirmed: number;
  confirmedPercent: number;
  arrived: number;
  noShows: number;
  cancelled: number;
  cancelledByPatient: number;
  whatsApp: number;
}

export interface DoctorDay {
  id: number;
  name: string;
  initials: string;
  hue: number;
  active: number;
  /** Where the "now" line goes: before the row at this index, or after them all; -1 off today. */
  nowIndex: number;
  rows: Row[];
}

export interface Row {
  id: number;
  time: string;
  patientName: string | null;
  patientPhone: string | null;
  initials: string;
  hue: number;
  status: "BOOKED" | "CONFIRMED" | "CANCELLED" | "NO_SHOW" | "ARRIVED";
  source: "WHATSAPP" | "MANUAL";
  cancelled: boolean;
  cancelledByPatient: boolean;
  past: boolean;
  canArrive: boolean;
  canNoShow: boolean;
  canCancel: boolean;
  canReschedule: boolean;
  /** The visit's fee and payments (Balance); null before any, and always for a doctor's login. */
  money: VisitMoney | null;
  /** Null once the patient was deleted. */
  patientId: number | null;
}

/**
 * The day's appointments per doctor, refreshed every 30 seconds: arrived, no-show (each with Undo),
 * move and cancel, as on the classic Today; the front desk also records payments here (Balance). The
 * next patient's Arrived is the big button, and a strip under the date shows how the day is going.
 */
export function TodayPage() {
  const t = useT();
  const [paying, setPaying] = useState<number | null>(null);
  const [adding, setAdding] = useState(false);
  const frontDesk = useSession().doctorId === null;
  const [params] = useSearchParams();
  const date = params.get("date");
  const today = useQuery({
    queryKey: ["today", date],
    queryFn: () => api<Today>(date ? `/api/today?date=${encodeURIComponent(date)}` : "/api/today"),
    refetchInterval: 30_000,
    placeholderData: keepPreviousData,
  });

  useEffect(() => {
    document.title = t("today.title");
  }, [t]);

  useDayKeys(today.data);

  if (!today.data) {
    return <PageSkeleton />;
  }
  const day = today.data;
  return (
    <main className="container">
      <header className="page-head day-hero">
        <DayRing stats={day.stats} />
        <div>
          <div className="eyebrow">
            <span>{t(day.relativeDayKey, day.relativeDayCount)}</span>
            <span className="live" title={t("today.refreshes")}><span className="live-dot" /><span>{t("today.live")}</span></span>
          </div>
          <h1>{day.dateLabel}</h1>
          <DayProgress stats={day.stats} />
        </div>
        <div className="head-actions">
          <DayNav day={day} />
          {frontDesk && (
            <button type="button" onClick={() => setAdding(true)}><Icon name="wallet" /><span>{t("money.add")}</span></button>
          )}
          <a className="button primary" href={`/appointments/new?date=${day.date}`}>
            <Icon name="plus" /><span>{t("today.add")}</span>
          </a>
        </div>
      </header>
      <StatsRow stats={day.stats} />
      <Doctors day={day} onPay={setPaying} />
      {paying !== null && <PaymentPanel appointmentId={paying} onClose={() => setPaying(null)} />}
      {adding && <AddMoneyDialog onClose={() => setAdding(false)} />}
    </main>
  );
}

function DayNav({ day }: { day: Today }) {
  const t = useT();
  const navigate = useNavigate();
  return (
    <nav className="segmented" aria-label={t("today.pick-date")}>
      <Link className="icon-btn" to={`/?date=${day.previousDate}`} title={t("today.previous")} aria-label={t("today.previous")}>
        <Icon name="chevron-left" flip />
      </Link>
      <Link to="/" className={day.today ? "current" : undefined}>{t("today.today")}</Link>
      <span className="date-input button ghost icon-btn" title={t("today.pick-date")}>
        <Icon name="today" />
        <input type="date" value={day.date} aria-label={t("today.pick-date")}
               onChange={(event) => event.target.value && navigate(`/?date=${event.target.value}`)} />
      </span>
      <Link className="icon-btn" to={`/?date=${day.nextDate}`} title={t("today.next")} aria-label={t("today.next")}>
        <Icon name="chevron-right" flip />
      </Link>
    </nav>
  );
}

/** ← and → change the day (mirrored in Arabic), T comes back to today: as on the old page. */
function useDayKeys(day: Today | undefined) {
  const navigate = useNavigate();
  const { language } = useSession();
  useEffect(() => {
    if (!day) {
      return;
    }
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement;
      if (event.ctrlKey || event.metaKey || event.altKey || target.closest("input, textarea, select, [contenteditable]")) {
        return;
      }
      const back = language === "ar" ? "ArrowRight" : "ArrowLeft";
      const forward = language === "ar" ? "ArrowLeft" : "ArrowRight";
      if (event.key === back) {
        navigate(`/?date=${day.previousDate}`);
      } else if (event.key === forward) {
        navigate(`/?date=${day.nextDate}`);
      } else if (event.key === "t") {
        navigate("/");
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [day, language, navigate]);
}

function StatsRow({ stats }: { stats: Stats }) {
  const t = useT();
  const whatsApp = useSession().whatsApp !== false;
  return (
    <div className="stats">
      <Stat label={t("today.stats.total")} value={stats.total} sub={whatsApp ? t("today.stats.whatsapp", stats.whatsApp) : undefined} />
      {whatsApp && <div className="stat tone-ok">
        <div className="stat-head"><span className="stat-label">{t("today.stats.confirmed")}</span></div>
        <div className="stat-value"><CountUp value={stats.confirmed} /></div>
        <div className="meter" role="presentation"><span style={{ width: `${stats.confirmedPercent}%` }} /></div>
        <div className="stat-sub">{t("today.stats.confirmed-share", stats.confirmedPercent)}</div>
      </div>}
      <Stat tone="tone-arrived" label={t("today.stats.arrived")} value={stats.arrived} />
      <Stat tone="tone-warn" label={t("today.stats.no-show")} value={stats.noShows} />
      <Stat tone="tone-danger" label={t("today.stats.cancelled")} value={stats.cancelled}
            sub={t("today.stats.cancelled-by-patient", stats.cancelledByPatient)} />
    </div>
  );
}

/** The ring's circle: radius 26 in a 64 by 64 box. */
const RING = 2 * Math.PI * 26;

/** How far the day has got, as a ring: arrived in green, no-shows after them, the count in the middle. */
function DayRing({ stats }: { stats: Stats }) {
  const arc = (n: number) => (stats.total === 0 ? 0 : (RING * n) / stats.total);
  const seen = arc(stats.arrived);
  return (
    <div className="day-ring" aria-hidden="true">
      <svg viewBox="0 0 64 64">
        <circle className="track" cx="32" cy="32" r="26" />
        {stats.arrived > 0 && <circle className="seen" cx="32" cy="32" r="26" strokeDasharray={`${seen} 400`} />}
        {stats.noShows > 0 && (
          <circle className="missed" cx="32" cy="32" r="26" strokeDasharray={`${arc(stats.noShows)} 400`} strokeDashoffset={-seen} />
        )}
      </svg>
      <span className="day-ring-label" dir="ltr">
        <strong><CountUp value={stats.arrived} /></strong><span>/{stats.total}</span>
      </span>
    </div>
  );
}

/** "6 of 9 arrived · 2 still to come · 1 no-show", beside the ring of the same parts. */
function DayProgress({ stats }: { stats: Stats }) {
  const t = useT();
  if (stats.total === 0) {
    return null;
  }
  const left = Math.max(stats.total - stats.arrived - stats.noShows, 0);
  return (
    <div className="day-progress">
      <span className="day-progress-text">
        {t("today.progress", stats.arrived, stats.total)}
        {left > 0 && <> · {t("today.progress.left", left)}</>}
        {stats.noShows > 0 && <> · {t("today.progress.no-show", stats.noShows)}</>}
      </span>
    </div>
  );
}

function Stat({ label, value, sub = "", tone }: { label: string; value: number; sub?: string; tone?: string }) {
  return (
    <div className={tone ? `stat ${tone}` : "stat"}>
      <div className="stat-head"><span className="stat-label">{label}</span></div>
      <div className="stat-value"><CountUp value={value} /></div>
      <div className="stat-sub">{sub}</div>
    </div>
  );
}

function Doctors({ day, onPay }: { day: Today; onPay: (appointmentId: number) => void }) {
  const t = useT();
  const session = useSession();
  if (day.doctors.length === 0) {
    return (
      <div className="card empty">
        <span className="empty-icon"><Icon name="users" /></span>
        <strong>{t("today.no-doctors")}</strong>
        <a className="button primary sm" href="/doctors"><Icon name="stethoscope" /><span>{t("nav.doctors")}</span></a>
      </div>
    );
  }
  return (
    <div className="doctors">
      {day.doctors.map((doctor) => (
        <article className="doctor-panel" key={doctor.id}>
          <header className="doctor-head">
            <span className="avatar lg" style={{ ["--hue" as string]: doctor.hue }}>{doctor.initials}</span>
            <div>
              <h2>{doctor.name}</h2>
              <span className="muted">{t("today.doctor.count", doctor.active)}</span>
            </div>
            <a className="button ghost icon-btn sm" href={`/appointments/new?date=${day.date}&doctorId=${doctor.id}`}
               title={t("today.add")} aria-label={t("today.add")}>
              <Icon name="calendar-plus" />
            </a>
            <span className="doctor-progress" aria-hidden="true"><span style={{ width: `${done(doctor.rows)}%` }} /></span>
          </header>
          {doctor.rows.length === 0 ? (
            <div className="empty">
              <span className="empty-icon"><Icon name="clock" /></span>
              <strong>{t("today.empty")}</strong>
              <p>{t(session.whatsApp !== false ? "today.empty.hint" : "today.empty.hint.no-whatsapp")}</p>
            </div>
          ) : (
            <ol className="timeline">
              {doctor.rows.map((row, index) => (
                <AppointmentItem key={row.id} row={row} nowLine={index === doctor.nowIndex ? day.nowTime : null} onPay={onPay}
                                 next={day.today && index === doctor.nowIndex && row.canArrive} />
              ))}
              {doctor.nowIndex === doctor.rows.length && <NowLine time={day.nowTime} />}
            </ol>
          )}
        </article>
      ))}
    </div>
  );
}

/** The share of a doctor's appointments that are over (arrived or no-show), out of those not cancelled. */
function done(rows: Row[]): number {
  const kept = rows.filter((row) => !row.cancelled);
  return kept.length === 0 ? 0 : (100 * kept.filter((row) => row.status === "ARRIVED" || row.status === "NO_SHOW").length) / kept.length;
}

/** True for a moment after the status changes, so the card can answer (app.css, .just-changed). */
function useJustChanged(status: string): boolean {
  const previous = useRef(status);
  const [changed, setChanged] = useState(false);
  useEffect(() => {
    if (previous.current === status) {
      return;
    }
    previous.current = status;
    setChanged(true);
    const timer = setTimeout(() => setChanged(false), 1100);
    return () => clearTimeout(timer);
  }, [status]);
  return changed;
}

function NowLine({ time }: { time: string }) {
  const t = useT();
  return <li className="now-line"><span>{t("today.now", time)}</span></li>;
}

function AppointmentItem({ row, nowLine, onPay, next = false }: {
  row: Row; nowLine: string | null; onPay: (id: number) => void; next?: boolean;
}) {
  const t = useT();
  const act = useAppointmentAction(row);
  // Money is the front desk's; a visit whose patient was deleted has no one to charge.
  const session = useSession();
  const canPay = session.doctorId === null && row.patientName !== null && !row.cancelled;
  const whatsApp = session.whatsApp !== false;
  const justChanged = useJustChanged(row.status);
  const classes = ["appt", `s-${row.status}`, row.cancelledByPatient ? "by-patient" : "", row.past ? "is-past" : "",
    next ? "is-next" : "", justChanged ? "just-changed" : ""]
    .filter(Boolean).join(" ");
  return (
    <>
      {nowLine !== null && <NowLine time={nowLine} />}
      <li className={classes} data-appointment={row.id}>
        <div className="appt-time">
          <strong>{row.time}</strong>
          {whatsApp && <span className={row.source === "WHATSAPP" ? "source whatsapp" : "source"}>
            <Icon name={row.source === "WHATSAPP" ? "chat" : "pencil"} /><span>{t(`source.${row.source}`)}</span>
          </span>}
        </div>
        <div className="appt-who">
          <span className="avatar sm" style={{ ["--hue" as string]: row.hue }}>{row.initials}</span>
          <div className="appt-name">
            <strong><PatientName id={row.patientId}>{row.patientName ?? t("patient.deleted")}</PatientName></strong>
            {row.patientPhone && (
              <a className="phone" href={`tel:${row.patientPhone}`} dir="ltr">
                <Icon name="phone" /><span>{row.patientPhone}</span>
              </a>
            )}
          </div>
        </div>
        <div className="appt-side">
          <span className="appt-status">
            {next && <span className="next-tag">{t("today.next-patient")}</span>}
            <span className={row.cancelledByPatient ? "chip chip-patient-cancelled" : `chip chip-${row.status}`}>
              {row.cancelledByPatient ? t("status.cancelled-by-patient") : t(`status.${row.status}`)}
            </span>
            {canPay && row.money && row.money.status !== "NONE" && <MoneyChip money={row.money} />}
          </span>
          <div className="appt-actions">
            {row.canArrive && (
              <button type="button" className={next ? "sm primary" : "sm"} disabled={act.isPending}
                      onClick={() => act.mutate("arrived")}>
                <Icon name="user-check" /><span>{t("action.arrived")}</span>
              </button>
            )}
            {row.canNoShow && (
              <button type="button" className="sm" disabled={act.isPending} onClick={() => act.mutate("no-show")}>
                <Icon name="user-x" /><span>{t("action.no-show")}</span>
              </button>
            )}
            {canPay && (
              <button type="button" className="sm" onClick={() => onPay(row.id)}>
                <Icon name="wallet" /><span>{t("money.pay")}</span>
              </button>
            )}
            {row.canReschedule && (
              <a className="button sm ghost icon-btn" href={`/appointments/${row.id}/reschedule`}
                 title={t("action.reschedule")} aria-label={t("action.reschedule")}><Icon name="move" /></a>
            )}
            {row.canCancel && (
              <button type="button" className="sm danger icon-btn" title={t("action.cancel")} aria-label={t("action.cancel")}
                      disabled={act.isPending}
                      onClick={() => window.confirm(t("action.cancel-confirm")) && act.mutate("cancel")}>
                <Icon name="ban" />
              </button>
            )}
          </div>
        </div>
      </li>
    </>
  );
}

type Action = "arrived" | "no-show" | "cancel";

/**
 * Arrived, no-show or cancel, then the day again. Arrived and no-show offer Undo for 5 seconds;
 * cancelling asked first. An action that no longer applies (cancelled meanwhile) says so.
 */
function useAppointmentAction(row: Row) {
  const t = useT();
  const toast = useToast();
  const queries = useQueryClient();
  const refresh = () => queries.invalidateQueries({ queryKey: ["today"] });
  const who = row.patientName ?? t("patient.deleted");
  return useMutation({
    mutationFn: (action: Action) => api<void>(`/api/appointments/${row.id}/${action}`, { method: "POST" }),
    onSuccess: (_, action) => {
      void refresh();
      if (action === "cancel") {
        toast({ text: t("today.toast.cancelled", who) });
        return;
      }
      toast({
        text: t(action === "arrived" ? "today.toast.arrived" : "today.toast.no-show", who),
        undo: () => api<void>(`/api/appointments/${row.id}/unmark`, { method: "POST" }).then(refresh),
      });
    },
    onError: (error) => {
      void refresh();
      toast({ text: t(error instanceof HttpError && error.status === 409 ? "error.not-allowed" : "error.generic"), error: true });
    },
  });
}

/** Paid, Partly paid ($10 left), Unpaid or Free: the visit's money at a glance. */
function MoneyChip({ money }: { money: VisitMoney }) {
  const t = useT();
  return (
    <span className={`chip money-${money.status}`}>
      {t(`money.status.${money.status}`)}
      {money.status === "PARTLY_PAID" && <> · <span dir="ltr">{t("money.left", usd(money.due))}</span></>}
    </span>
  );
}
