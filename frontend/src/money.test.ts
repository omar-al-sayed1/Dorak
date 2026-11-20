import { describe, expect, it } from "vitest";
import { cents, fromLbp, lbp, parseAmount, toLbp, usd } from "./money";

describe("money", () => {
  it("shows dollars to the cent and lira whole, with Western digits", () => {
    expect(usd(1250)).toBe("$1,250.00");
    expect(usd(0.5)).toBe("$0.50");
    expect(lbp(1342500)).toBe("1,342,500 LBP");
  });

  it("converts at the rate as the server records it", () => {
    expect(toLbp(15, 89500)).toBe(1342500);
    expect(fromLbp(1342500, 89500)).toBe(15);
    expect(fromLbp(1000000, 89500)).toBe(11.17);
    expect(cents(0.1 + 0.2)).toBe(0.3);
  });

  it("reads typed amounts, with or without thousands separators", () => {
    expect(parseAmount("30")).toBe(30);
    expect(parseAmount(" 30.5 ")).toBe(30.5);
    expect(parseAmount("1,342,500")).toBe(1342500);
    expect(parseAmount("")).toBeNull();
    expect(parseAmount("-5")).toBeNull();
    expect(parseAmount("12abc")).toBeNull();
  });
});
