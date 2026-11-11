import { useEffect } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useSearchParams } from "react-router";
import { api } from "../api";
import { useT } from "../i18n";
import { Icon } from "../icons";
import { useSession } from "../session";
import { PageSkeleton } from "../skeletons";

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
}

/** The day's appointments per doctor, refreshed every 30 seconds. Actions come in the next step (R2). */
export function TodayPage() {
  const t = useT();
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
      <header className="page-head">
        <div>
          <div className="eyebrow">
            <span>{t(day.relativeDayKey, day.relativeDayCount)}</span>
            <span className="live" title={t("today.refreshes")}><span className="live-dot" /><span>{t("today.live")}</span></span>
          </div>
          <h1>{day.dateLabel}</h1>
        </div>
        <div className="head-actions">
          <DayNav day={day} />
          <a className="button primary" href={`/appointments/new?date=${day.date}`}>
            <Icon name="plus" /><span>{t("today.add")}</span>
          </a>
        </div>
      </header>
      <StatsRow stats={day.stats} />
      <Doctors day={day} />
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
  return (
    <div className="stats">
      <Stat label={t("today.stats.total")} icon="today" value={stats.total} sub={t("today.stats.whatsapp", stats.whatsApp)} />
      <div className="stat tone-ok">
        <div className="stat-head"><span className="stat-label">{t("today.stats.confirmed")}</span>
          <span className="stat-icon"><Icon name="check-circle" /></span></div>
        <div className="stat-value">{stats.confirmed}</div>
        <div className="meter" role="presentation"><span style={{ width: `${stats.confirmedPercent}%` }} /></div>
        <div className="stat-sub">{t("today.stats.confirmed-share", stats.confirmedPercent)}</div>
      </div>
      <Stat tone="tone-arrived" label={t("today.stats.arrived")} icon="user-check" value={stats.arrived} />
      <Stat tone="tone-warn" label={t("today.stats.no-show")} icon="user-x" value={stats.noShows} />
      <Stat tone="tone-danger" label={t("today.stats.cancelled")} icon="ban" value={stats.cancelled}
            sub={t("today.stats.cancelled-by-patient", stats.cancelledByPatient)} />
    </div>
  );
}

function Stat({ label, icon, value, sub = "", tone }: { label: string; icon: string; value: number; sub?: string; tone?: string }) {
  return (
    <div className={tone ? `stat ${tone}` : "stat"}>
      <div className="stat-head"><span className="stat-label">{label}</span>
        <span className="stat-icon"><Icon name={icon} /></span></div>
      <div className="stat-value">{value}</div>
      <div className="stat-sub">{sub}</div>
    </div>
  );
}

function Doctors({ day }: { day: Today }) {
  const t = useT();
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
          </header>
          {doctor.rows.length === 0 ? (
            <div className="empty">
              <span className="empty-icon"><Icon name="clock" /></span>
              <strong>{t("today.empty")}</strong>
              <p>{t("today.empty.hint")}</p>
            </div>
          ) : (
            <ol className="timeline">
              {doctor.rows.map((row, index) => (
                <AppointmentItem key={row.id} row={row} nowLine={index === doctor.nowIndex ? day.nowTime : null} />
              ))}
              {doctor.nowIndex === doctor.rows.length && <NowLine time={day.nowTime} />}
            </ol>
          )}
        </article>
      ))}
    </div>
  );
}

function NowLine({ time }: { time: string }) {
  const t = useT();
  return <li className="now-line"><span>{t("today.now", time)}</span></li>;
}

function AppointmentItem({ row, nowLine }: { row: Row; nowLine: string | null }) {
  const t = useT();
  const classes = ["appt", `s-${row.status}`, row.cancelledByPatient ? "by-patient" : "", row.past ? "is-past" : ""]
    .filter(Boolean).join(" ");
  return (
    <>
      {nowLine !== null && <NowLine time={nowLine} />}
      <li className={classes} data-appointment={row.id}>
        <div className="appt-time">
          <strong>{row.time}</strong>
          <span className={row.source === "WHATSAPP" ? "source whatsapp" : "source"}>
            <Icon name={row.source === "WHATSAPP" ? "chat" : "pencil"} /><span>{t(`source.${row.source}`)}</span>
          </span>
        </div>
        <div className="appt-who">
          <span className="avatar sm" style={{ ["--hue" as string]: row.hue }}>{row.initials}</span>
          <div className="appt-name">
            <strong>{row.patientName ?? t("patient.deleted")}</strong>
            {row.patientPhone && (
              <a className="phone" href={`tel:${row.patientPhone}`} dir="ltr">
                <Icon name="phone" /><span>{row.patientPhone}</span>
              </a>
            )}
          </div>
        </div>
        <div className="appt-side">
          <span className={row.cancelledByPatient ? "chip chip-patient-cancelled" : `chip chip-${row.status}`}>
            {row.cancelledByPatient ? t("status.cancelled-by-patient") : t(`status.${row.status}`)}
          </span>
        </div>
      </li>
    </>
  );
}
