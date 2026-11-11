/** Grey shimmering shapes while a page loads, as on the old pages (skeletons.html, app.css). */
export function PageSkeleton() {
  return (
    <div className="container page-skeleton-inner" aria-hidden="true">
      <span className="sk sk-line w-15" />
      <span className="sk sk-title" />
      <div className="sk-toolbar">
        <span className="sk sk-pill" />
        <span className="sk sk-pill" />
        <span className="sk sk-pill wide" />
      </div>
      <div className="sk-cards">
        {[1, 2, 3].map((i) => (
          <div className="skeleton-card sk-card" key={i}>
            <div className="sk-card-head">
              <span className="sk sk-circle sk-avatar-lg" />
              <span className="sk-lines">
                <span className="sk sk-line w-50" />
                <span className="sk sk-line w-30" />
              </span>
            </div>
            <span className="sk sk-line w-90" />
            <span className="sk sk-line w-75" />
            <span className="sk sk-line w-60" />
          </div>
        ))}
      </div>
    </div>
  );
}
