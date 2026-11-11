// The dashboard's icon sprite, the very file the server-rendered pages include: one set of icons.
import spriteFile from "../../src/main/resources/templates/dashboard/icons.html?raw";

/** The <svg> of the Thymeleaf fragment, without its Thymeleaf attribute. */
export const SPRITE = (spriteFile.match(/<svg[\s\S]*<\/svg>/)?.[0] ?? "").replace(/\s+th:fragment="[^"]*"/, "");

/** Once per page: the symbols every {@link Icon} points to. */
export function IconSprite() {
  return <div hidden dangerouslySetInnerHTML={{ __html: SPRITE }} />;
}

/**
 * An icon of the sprite, e.g. {@code <Icon name="today" />}. Icons with a direction (chevrons)
 * take {@code flip}, so they mirror in Arabic.
 */
export function Icon({ name, flip = false }: { name: string; flip?: boolean }) {
  return (
    <svg className={flip ? "icon flip-rtl" : "icon"} aria-hidden="true">
      <use href={`#i-${name}`} />
    </svg>
  );
}
