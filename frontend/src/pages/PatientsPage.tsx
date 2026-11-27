import { useEffect, useRef, useState } from "react";
import { keepPreviousData, useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router";
import { api } from "../api";
import { AddMoneyDialog, type Picked } from "../balance/AddMoneyDialog";
import { CountUp } from "../CountUp";
import { useT } from "../i18n";
import { Icon } from "../icons";
import { usd } from "../money";
import { patientsUrl, relativeDay, type Counts, type Filter, type PatientRow, type PatientsPage as Page,
  type Sort } from "../patients";
import { useSession } from "../session";
import { PageSkeleton } from "../skeletons";

const FILTERS: Array<{ key: Filter; count: keyof Counts; frontDesk?: boolean; whatsApp?: boolean }> = [
  { key: "all", count: "all" },
  { key: "booked", count: "booked" },
  { key: "unbooked", count: "unbooked" },
  { key: "owes", count: "owes", frontDesk: true },
  { key: "family", count: "family", frontDesk: true },
  { key: "opted-out", count: "optedOut", whatsApp: true },
];

/** The direction a column sorts in first: names A to Z and the soonest appointment, the rest biggest or latest first. */
const FIRST_DIRECTION: Record<Sort, "asc" | "desc"> = { name: "asc", next: "asc", last: "desc", visits: "desc", balance: "desc" };

/**
 * Every patient in a table: search by name or number, filters with their counts, sort by any
 * column. Family members on one number share a coloured stripe; visits show as dots; the front desk
 * sees balances and adds money from a row. Loads 30 at a time as you scroll.
 */
export function PatientsPage() {
  const t = useT();
  const session = useSession();
  const frontDesk = session.doctorId === null;
  const whatsApp = session.whatsApp !== false;
  const [params, setParams] = useSearchParams();
  const q = params.get("q") ?? "";
  const filter = (params.get("filter") ?? "all") as Filter;
  const sort = (params.get("sort") ?? "name") as Sort;
  const dir = (params.get("dir") ?? FIRST_DIRECTION[sort]) as "asc" | "desc";
  const [typed, setTyped] = useState(q);
  const [adding, setAdding] = useState<Picked | null | undefined>(undefined);
  const [notice, setNotice] = useState<string | null>(null);
  const search = useRef<HTMLInputElement>(null);

  const change = (next: Record<string, string | null>) => {
    const merged = new URLSearchParams(params);
    for (const [key, value] of Object.entries(next)) {
      if (value === null || value === "") {
        merged.delete(key);
      } else {
        merged.set(key, value);
      }
    }
    setParams(merged, { replace: true });
  };

  // Results follow the typing, a moment after it stops.
  useEffect(() => {
    const timer = setTimeout(() => typed.trim() !== q && change({ q: typed.trim() }), 250);
    return () => clearTimeout(timer);
    // Only the typing starts a search (not a change of filter or sort).
  }, [typed]);

  useEffect(() => {
    document.title = t("patients.title");
  }, [t]);

  // "/" jumps to the search, as on the old page.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement;
      if (event.key === "/" && !target.closest("input, textarea, select, [contenteditable]")) {
        event.preventDefault();
        search.current?.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  const list = useInfiniteQuery({
    queryKey: ["patients", "table", q, filter, sort, dir],
    queryFn: ({ pageParam }) => api<Page>(patientsUrl({ q, filter, sort, dir, page: pageParam })),
    initialPageParam: 0,
    getNextPageParam: (last) => last.nextPage ?? undefined,
    placeholderData: keepPreviousData,
  });

  const sortBy = (column: Sort) => {
    const direction = column === sort ? (dir === "asc" ? "desc" : "asc") : FIRST_DIRECTION[column];
    change({ sort: column === "name" ? null : column, dir: direction === FIRST_DIRECTION[column] ? null : direction });
  };

  if (!list.data) {
    return <PageSkeleton />;
  }
  const first = list.data.pages[0];
  const rows = list.data.pages.flatMap((page) => page.patients);
  return (
    <main className="container">
      <header className="page-head">
        <div>
          <h1>{t("patients.title")}</h1>
          <p className="muted">{t("patients.count", first?.counts.all ?? 0)}</p>
        </div>
        <div className="head-actions">
          {frontDesk && (
            <button type="button" onClick={() => setAdding(null)}><Icon name="wallet" /><span>{t("money.add")}</span></button>
          )}
          <a className="button primary" href="/appointments/new"><Icon name="calendar-plus" /><span>{t("today.add")}</span></a>
        </div>
      </header>

      {notice && <p className="notice" role="status"><Icon name="check-circle" /><span>{t(notice)}</span></p>}

      <form className="big-search" role="search" onSubmit={(event) => { event.preventDefault(); change({ q: typed.trim() }); }}>
        <Icon name="search" />
        <input ref={search} type="search" value={typed} onChange={(event) => setTyped(event.target.value)}
               placeholder={t("patients.search")} aria-label={t("patients.search")} autoComplete="off" dir="auto" />
        <kbd className="kbd-hint">/</kbd>
      </form>

      {/* Each filter as a tile: its count, and along its foot its share of all the patients found. */}
      <nav className="filter-tiles" aria-label={t("patients.filters")}>
        {FILTERS.filter((f) => (frontDesk || !f.frontDesk) && (whatsApp || !f.whatsApp)).map((f) => {
          const count = first?.counts[f.count] ?? 0;
          const all = first?.counts.all ?? 0;
          return (
            <button key={f.key} type="button" aria-pressed={filter === f.key}
                    className={`filter-tile filter-${f.key}${filter === f.key ? " on" : ""}`}
                    style={{ ["--share" as string]: all === 0 ? 0 : count / all }}
                    onClick={() => change({ filter: f.key === "all" ? null : f.key })}>
              <span className="filter-count"><CountUp value={count} /></span>
              <span className="tile-label">{t(`patients.filter.${f.key}`)}</span>
            </button>
          );
        })}
      </nav>

      {rows.length === 0 ? (
        <div className="card empty">
          <span className="empty-icon"><Icon name="search" /></span>
          <strong>{t("patients.empty")}</strong>
          <p>{t("patients.hint")}</p>
        </div>
      ) : (
        <div className="card ptable-card" aria-busy={list.isFetching}>
          <table className="ptable">
            <thead>
              <tr>
                <SortHeader column="name" label={t("patients.col.patient")} sort={sort} dir={dir} onSort={sortBy} />
                <th scope="col" className="col-phone">{t("patients.phone")}</th>
                <SortHeader column="next" label={t("patients.next")} sort={sort} dir={dir} onSort={sortBy} />
                <SortHeader column="last" label={t("patients.col.last")} sort={sort} dir={dir} onSort={sortBy} />
                <SortHeader column="visits" label={t("patients.col.visits")} sort={sort} dir={dir} onSort={sortBy} />
                {frontDesk && <SortHeader column="balance" label={t("patients.col.balance")} sort={sort} dir={dir} onSort={sortBy} />}
                <th scope="col"><span className="sr-only">{t("patients.col.actions")}</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <PatientLine key={row.id} row={row} frontDesk={frontDesk}
                             onAdd={() => setAdding({ id: row.id, name: row.name, phone: row.phone, initials: row.initials,
                               hue: row.hue, balance: row.balance ?? 0 })}
                             onDeleted={() => setNotice("notice.patient-deleted")} />
              ))}
            </tbody>
          </table>
          {list.hasNextPage && <MoreRows loading={list.isFetchingNextPage} onMore={() => void list.fetchNextPage()} />}
        </div>
      )}
      {adding !== undefined && <AddMoneyDialog patient={adding} onClose={() => setAdding(undefined)} />}
    </main>
  );
}

function SortHeader({ column, label, sort, dir, onSort }: {
  column: Sort; label: string; sort: Sort; dir: "asc" | "desc"; onSort: (column: Sort) => void;
}) {
  const t = useT();
  const active = sort === column;
  return (
    <th scope="col" className={`col-${column}`} aria-sort={active ? (dir === "asc" ? "ascending" : "descending") : "none"}>
      <button type="button" className={active ? "sort on" : "sort"} onClick={() => onSort(column)}
              title={t("patients.sort", label)}>
        <span>{label}</span>
        <Icon name={active && dir === "desc" ? "chevron-down" : "chevron-up"} />
      </button>
    </th>
  );
}

function PatientLine({ row, frontDesk, onAdd, onDeleted }: {
  row: PatientRow; frontDesk: boolean; onAdd: () => void; onDeleted: () => void;
}) {
  const t = useT();
  const whatsApp = useSession().whatsApp !== false;
  const queries = useQueryClient();
  const remove = useMutation({
    mutationFn: () => api<void>(`/api/patients/${row.id}`, { method: "DELETE" }),
    onSuccess: () => {
      onDeleted();
      void queries.invalidateQueries({ queryKey: ["patients"] });
      void queries.invalidateQueries({ queryKey: ["money"] });
      void queries.invalidateQueries({ queryKey: ["today"] });
    },
  });
  const family = row.family.length > 0;
  const book = `/appointments/new?phone=${encodeURIComponent(row.phone)}&name=${encodeURIComponent(row.name)}`;
  return (
    <tr className={family ? "has-family" : undefined} style={{ ["--family-hue" as string]: row.familyHue }}>
      <td className="col-name">
        <div className="who">
          <span className="avatar" style={{ ["--hue" as string]: row.hue }}>{row.initials}</span>
          <div className="who-text">
            {frontDesk ? <Link to={`/patients/${row.id}`}><strong>{row.name}</strong></Link> : <strong>{row.name}</strong>}
            <span className="who-tags">
              <span className="tag">{t(`language.${row.language}`)}</span>
              {row.optedOut && whatsApp && <span className="tag tag-danger" title={t("patients.opted-out")}><Icon name="ban" />
                <span>{t("patients.filter.opted-out")}</span></span>}
              {family && <span className="family-tag" title={t("patients.family", row.family.join(", "))}>
                <Icon name="users" /><span>{row.family.join(", ")}</span></span>}
            </span>
          </div>
        </div>
      </td>
      <td className="col-phone" data-label={t("patients.phone")}>
        <a className="phone" href={`tel:${row.phone}`} dir="ltr">{row.phone}</a>
      </td>
      <td className="col-next" data-label={t("patients.next")}>
        {row.next ? (
          <a className={row.next.days <= 1 ? "next-cell soon" : "next-cell"} href={`/appointments/${row.next.appointmentId}`}>
            <strong>{relativeDay(t, row.next.days)}</strong>
            <span className="small">{row.next.label}{row.next.doctor && <> · {row.next.doctor}</>}</span>
          </a>
        ) : <span className="muted">—</span>}
      </td>
      <td className="col-last" data-label={t("patients.col.last")}>
        {row.lastVisit !== null && row.lastVisitDays !== null ? (
          <span className="last-cell" title={row.lastVisit}>
            <span>{relativeDay(t, -row.lastVisitDays)}</span>
            <span className="small muted">{row.lastVisit}</span>
          </span>
        ) : <span className="muted">{t("patients.never")}</span>}
      </td>
      <td className="col-visits" data-label={t("patients.col.visits")}>
        <VisitDots visits={row.visits} />
      </td>
      {frontDesk && (
        <td className="col-balance" data-label={t("patients.col.balance")}>
          <BalancePill balance={row.balance ?? 0} />
        </td>
      )}
      <td className="col-actions">
        <div className="row-actions">
          <a className="button ghost sm icon-btn" href={`tel:${row.phone}`} title={t("attention.call")} aria-label={t("attention.call")}>
            <Icon name="phone" />
          </a>
          {frontDesk && (
            <button type="button" className="ghost sm icon-btn" onClick={onAdd} title={t("money.add")} aria-label={t("money.add")}>
              <Icon name="wallet" />
            </button>
          )}
          <a className="button ghost sm icon-btn" href={book} title={t("patients.book")} aria-label={t("patients.book")}>
            <Icon name="calendar-plus" />
          </a>
          {frontDesk && (
            <button type="button" className="sm icon-btn ghost-danger" title={t("action.delete")} aria-label={t("action.delete")}
                    disabled={remove.isPending}
                    onClick={() => window.confirm(t("patients.delete-confirm")) && remove.mutate()}>
              <Icon name="trash" />
            </button>
          )}
        </div>
      </td>
    </tr>
  );
}

/** One dot per visit, up to five, then "+7": regulars stand out at a glance. */
function VisitDots({ visits }: { visits: number }) {
  const t = useT();
  return (
    <span className="visit-dots" title={t("patients.visits", visits)} aria-label={t("patients.visits", visits)}>
      {visits === 0 ? <span className="muted">0</span> : (
        <>
          {Array.from({ length: Math.min(visits, 5) }, (_, index) => <span key={index} className="dot" />)}
          {visits > 5 && <span className="dots-more">+{visits - 5}</span>}
        </>
      )}
    </span>
  );
}

function BalancePill({ balance }: { balance: number }) {
  const t = useT();
  if (balance === 0) {
    return <span className="muted">—</span>;
  }
  return balance > 0
    ? <span className="pay-badge owing" title={t("ledger.owes", usd(balance))} dir="ltr">{usd(balance)}</span>
    : <span className="pay-badge credit" title={t("ledger.credit", usd(-balance))} dir="ltr">+{usd(-balance)}</span>;
}

/** Loads the next page when it scrolls into view; also a button, for keyboards. */
function MoreRows({ loading, onMore }: { loading: boolean; onMore: () => void }) {
  const t = useT();
  const sentinel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const element = sentinel.current;
    if (!element || typeof IntersectionObserver === "undefined") {
      return;
    }
    const observer = new IntersectionObserver((entries) => entries.some((entry) => entry.isIntersecting) && onMore());
    observer.observe(element);
    return () => observer.disconnect();
  }, [onMore]);
  return (
    <div className="table-more" ref={sentinel}>
      <button type="button" className="ghost sm" disabled={loading} onClick={onMore}>{t("list.load-more")}</button>
    </div>
  );
}
