import { describe, expect, it } from "vitest";
import { format, translator } from "./i18n";

describe("texts", () => {
  it("fills arguments and reads '' as an apostrophe, as MessageFormat does", () => {
    expect(format("{0} of {1} appointments", "k", [3, "8"])).toBe("3 of 8 appointments");
    expect(format("Rana''s {0}", "k", ["day"])).toBe("Rana's day");
    expect(format("موعدك مع {0}", "k", ["د. خوري"])).toBe("موعدك مع د. خوري");
  });

  it("leaves a text without arguments exactly as written, and shows the key of a missing text", () => {
    expect(format("Don't book", "k", [])).toBe("Don't book");
    expect(format("{0} and {1}", "k", ["a"])).toBe("a and {1}");
    expect(translator({})("today.title")).toBe("today.title");
  });
});
