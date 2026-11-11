/**
 * Light, dark or the device's theme, and the collapsed sidebar: the same settings, kept the same
 * way (localStorage "theme", "sidebar"), as on the old pages, so switching between them keeps both.
 */

export type Theme = "auto" | "light" | "dark";

const root = document.documentElement;

export function currentTheme(): Theme {
  const theme = root.dataset.theme;
  return theme === "light" || theme === "dark" ? theme : "auto";
}

export function chooseTheme(theme: Theme): void {
  if (theme === "auto") {
    delete root.dataset.theme;
  } else {
    root.dataset.theme = theme;
  }
  remember("theme", theme === "auto" ? null : theme);
}

export function isSidebarCollapsed(): boolean {
  return root.dataset.sidebar === "collapsed";
}

export function collapseSidebar(collapsed: boolean): void {
  if (collapsed) {
    root.dataset.sidebar = "collapsed";
  } else {
    delete root.dataset.sidebar;
  }
  remember("sidebar", collapsed ? "collapsed" : null);
}

/** Storage can be refused (private windows): the choice then lasts until the page is closed. */
function remember(key: string, value: string | null): void {
  try {
    if (value === null) {
      localStorage.removeItem(key);
    } else {
      localStorage.setItem(key, value);
    }
  } catch {
    // Not kept.
  }
}
