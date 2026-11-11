import { createContext, useContext, type ReactNode } from "react";

/**
 * The dashboard's texts, from messages_en/ar.properties (GET /api/messages): one source for the
 * server-rendered pages and React.
 */

export type Messages = Record<string, string>;

export type Translate = (key: string, ...args: Array<string | number>) => string;

/**
 * A text with its arguments, as Java's MessageFormat does it for texts with arguments:
 * {0}, {1}... filled, and '' read as an apostrophe. A missing text shows its key, so it gets noticed.
 */
export function format(pattern: string | undefined, key: string, args: Array<string | number>): string {
  if (pattern === undefined) {
    return key;
  }
  if (args.length === 0) {
    return pattern;
  }
  return pattern
    .replace(/\{(\d+)\}/g, (match, index: string) => {
      const value = args[Number(index)];
      return value === undefined ? match : String(value);
    })
    .replace(/''/g, "'");
}

export function translator(messages: Messages): Translate {
  return (key, ...args) => format(messages[key], key, args);
}

const I18n = createContext<Translate>((key) => key);

export function I18nProvider({ messages, children }: { messages: Messages; children: ReactNode }) {
  return <I18n.Provider value={translator(messages)}>{children}</I18n.Provider>;
}

/** {@code const t = useT(); t("today.title")}. */
export function useT(): Translate {
  return useContext(I18n);
}
