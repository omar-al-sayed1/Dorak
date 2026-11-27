import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { Link, useLocation } from "react-router";
import { logOut } from "./api";
import { useT } from "./i18n";
import { Icon, IconSprite } from "./icons";
import { useLanguageSwitch, useSession } from "./session";
import { chooseTheme, collapseSidebar, currentTheme, isSidebarCollapsed, type Theme } from "./theme";

/**
 * The page around every page: the sidebar (a bottom bar on phones), the account menu and the
 * Needs attention bell, as the old pages' layout.html draws them, with the same CSS classes.
 * Pages React doesn't show yet are plain links to the old pages.
 */

interface NavItem {
  key: string;
  label: string;
  icon: string;
  /** In the React app (a route), or an old page (a full page load). */
  to?: string;
  href?: string;
  className?: string;
}

export function Shell({ children }: { children: ReactNode }) {
  const t = useT();
  const session = useSession();
  const location = useLocation();
  const active = location.pathname === "/" ? "today" : location.pathname.slice(1).split("/")[0];
  const [collapsed, setCollapsed] = useState(isSidebarCollapsed);

  const items: NavItem[] = session.clinicId === null
    ? [
        { key: "operator", label: t("nav.clinics"), icon: "building", href: "/operator" },
        { key: "operator-patients", label: t("operator.patients.title"), icon: "users", href: "/operator/patients" },
        { key: "operator-report", label: t("nav.report"), icon: "chart", href: "/operator/report" },
        { key: "operator-billing", label: t("nav.billing"), icon: "wallet", href: "/operator/billing" },
        { key: "operator-rates", label: t("nav.rates"), icon: "message", href: "/operator/rates" },
      ]
    : [
        { key: "today", label: t("nav.today"), icon: "today", to: "/" },
        { key: "week", label: t("nav.week"), icon: "week", href: "/week" },
        { key: "patients", label: t("nav.patients"), icon: "users", to: "/patients" },
        // Phones: in the account menu instead, so the bottom bar keeps five items at most.
        { key: "report", label: t("nav.report"), icon: "chart", href: "/report", className: "nav-report" },
        ...(session.doctorId === null
          ? [{ key: "balance", label: t("nav.balance"), icon: "wallet", to: "/balance", className: "nav-report" }]
          : []),
        ...(session.doctorId !== null
          ? [{ key: "doctors", label: t("nav.my-hours"), icon: "clock", href: `/doctors/${session.doctorId}` }]
          : [
              { key: "doctors", label: t("nav.doctors"), icon: "stethoscope", href: "/doctors" },
              { key: "settings", label: t("nav.settings"), icon: "settings", href: "/settings" },
            ]),
      ];

  const toggleSidebar = () => {
    collapseSidebar(!collapsed);
    setCollapsed(!collapsed);
  };

  // The lit shape behind the current page's item: measured, so it can slide to the next one (app.css, .nav-glider).
  const nav = useRef<HTMLElement>(null);
  const [glider, setGlider] = useState<{ y: number; h: number } | null>(null);
  useLayoutEffect(() => {
    const place = () => {
      const link = nav.current?.querySelector<HTMLElement>("a.active");
      setGlider(link && link.offsetHeight > 0 ? { y: link.offsetTop, h: link.offsetHeight } : null);
    };
    place();
    window.addEventListener("resize", place);
    void document.fonts?.ready.then(place);
    return () => window.removeEventListener("resize", place);
  }, [active, collapsed, items.length]);

  return (
    <>
      <IconSprite />
      <div className="shell">
        <aside className="sidebar" id="sidebar">
          <div className="side-head">
            <Link className="brand" to="/" title={session.clinicName ?? t("app.name")}>
              <img className="brand-logo" src="/img/dorak-mark.png" alt="" width={40} height={40} />
              <span className="brand-text">
                <span className="brand-name">{session.clinicName ?? t("app.name")}</span>
                <span className="brand-sub">{t("app.name")}</span>
              </span>
            </Link>
            <button type="button" className="side-toggle" onClick={toggleSidebar} aria-controls="sidebar"
                    aria-expanded={!collapsed} aria-label={t(collapsed ? "nav.expand" : "nav.collapse")}
                    data-tip={t(collapsed ? "nav.expand" : "nav.collapse")} aria-keyshortcuts="[">
              <svg className="icon flip-rtl" viewBox="0 0 24 24" aria-hidden="true">
                <rect x="3.5" y="4.5" width="17" height="15" rx="2.5" />
                <path d="M9.5 4.5v15" />
                <path className="toggle-arrow" d="M16 9.5l-2.5 2.5 2.5 2.5" />
              </svg>
            </button>
          </div>

          <nav className={glider ? "side-nav has-glider" : "side-nav"} aria-label={t("app.name")} ref={nav}>
            {glider && (
              <span className="nav-glider" aria-hidden="true"
                    style={{ ["--y" as string]: `${glider.y}px`, ["--h" as string]: `${glider.h}px` }} />
            )}
            <span className="nav-label">{t(session.clinicId === null ? "operator.title" : "nav.section")}</span>
            {items.map((item) => (
              <NavLink key={item.key} item={item} active={active === item.key} />
            ))}
          </nav>

          <div className="side-foot">
            <div className="account-row">
              <AccountMenu active={active} />
              {session.clinicId !== null && session.doctorId === null && session.whatsApp !== false && <Bell active={active === "attention"} />}
            </div>
          </div>
        </aside>
        <div className="content">
          <BillingBanner />
          {children}
        </div>
      </div>
    </>
  );
}

/** The clinic's payment for the service is near or late: a bar across the page, as on the classic pages (layout.html). */
function BillingBanner() {
  const t = useT();
  const notice = useSession().billing;
  if (!notice) {
    return null;
  }
  const text = t(`billing.banner.${notice.status}`, notice.dueOn, notice.days);
  const className = `bill-banner is-${notice.status}`;
  return notice.owner ? (
    <a className={className} href="/billing" role="status">
      <Icon name="wallet" /><span className="grow">{text}</span>
      <span className="go"><span>{t("billing.banner.link")}</span><Icon name="chevron-right" flip /></span>
    </a>
  ) : (
    <div className={className} role="status"><Icon name="wallet" /><span className="grow">{text}</span></div>
  );
}

function NavLink({ item, active }: { item: NavItem; active: boolean }) {
  const className = [item.className, active ? "active" : ""].filter(Boolean).join(" ") || undefined;
  const content = <><Icon name={item.icon} /><span>{item.label}</span></>;
  return item.to !== undefined
    ? <Link to={item.to} className={className} aria-current={active ? "page" : undefined}>{content}</Link>
    : <a href={item.href} className={className} aria-current={active ? "page" : undefined}>{content}</a>;
}

function Bell({ active }: { active: boolean }) {
  const t = useT();
  const count = useSession().attentionCount ?? 0;
  return (
    <a className={["bell", active ? "active" : "", count > 0 ? "has-alerts" : ""].filter(Boolean).join(" ")}
       href="/attention" title={t("nav.attention")}
       aria-label={count > 0 ? `${t("nav.attention")} (${count})` : t("nav.attention")}>
      <Icon name="bell" />
      {count > 0 && <span className="nav-badge" aria-hidden="true">{count}</span>}
    </a>
  );
}

/** Theme, language, password, the old dashboard and logging out; on phones also Needs attention, Report and Balance. */
function AccountMenu({ active }: { active: string }) {
  const t = useT();
  const session = useSession();
  const switchLanguage = useLanguageSwitch();
  const [theme, setTheme] = useState<Theme>(currentTheme);
  const menu = useRef<HTMLDetailsElement>(null);
  const other = session.language === "ar" ? "en" : "ar";
  const count = session.attentionCount ?? 0;

  // Closes when clicking anywhere else, as the old menu does.
  useEffect(() => {
    const close = (event: PointerEvent) => {
      if (menu.current?.open && !menu.current.contains(event.target as Node)) {
        menu.current.open = false;
      }
    };
    document.addEventListener("pointerdown", close);
    return () => document.removeEventListener("pointerdown", close);
  }, []);

  const pick = (choice: Theme) => {
    chooseTheme(choice);
    setTheme(choice);
  };

  return (
    <details className="menu" ref={menu}>
      <summary aria-label={t("nav.menu")}>
        <span className="avatar sm" style={{ ["--hue" as string]: session.hue }}>{session.initials}</span>
        {count > 0 && <span className="avatar-alert" aria-hidden="true" />}
        <span className="menu-who">
          <strong>{session.username}</strong>
          <span>{t(`role.${session.role}`)}</span>
        </span>
        <Icon name="chevron-up" />
      </summary>
      <div className="menu-panel">
        {session.clinicId !== null && (
          <>
            {session.doctorId === null && session.whatsApp !== false && (
              <a className={active === "attention" ? "menu-attention active" : "menu-attention"} href="/attention">
                <Icon name="bell" /><span>{t("nav.attention")}</span>
                {count > 0 && <span className="nav-badge">{count}</span>}
              </a>
            )}
            <a className={active === "report" ? "menu-report active" : "menu-report"} href="/report">
              <Icon name="chart" /><span>{t("nav.report")}</span>
            </a>
            {session.doctorId === null && (
              <Link className={active === "balance" ? "menu-report active" : "menu-report"} to="/balance">
                <Icon name="wallet" /><span>{t("nav.balance")}</span>
              </Link>
            )}
            <hr className="menu-attention-rule" />
          </>
        )}
        <div className="menu-title">{t("theme.title")}</div>
        <div className="theme-switch" role="group" aria-label={t("theme.title")}>
          {(["auto", "light", "dark"] as const).map((choice) => (
            <button key={choice} type="button" aria-pressed={theme === choice} onClick={() => pick(choice)}>
              <Icon name={{ auto: "monitor", light: "sun", dark: "moon" }[choice]} />
              <span>{t(`theme.${choice}`)}</span>
            </button>
          ))}
        </div>
        <button type="button" className="menu-item" lang={other} disabled={switchLanguage.isPending}
                onClick={() => switchLanguage.mutate(other)}>
          <Icon name="globe" /><span>{t(`language.switch.${other}`)}</span>
        </button>
        {session.role === "OWNER" && <a href="/billing"><Icon name="receipt" /><span>{t("nav.billing")}</span></a>}
        <a href="/account/password"><Icon name="key" /><span>{t("nav.password")}</span></a>
        <a href="/"><Icon name="monitor" /><span>{t("nav.classic")}</span></a>
        <hr />
        <button type="button" className="menu-item" onClick={() => void logOut()}>
          <Icon name="logout" flip /><span>{t("nav.logout")}</span>
        </button>
      </div>
    </details>
  );
}
