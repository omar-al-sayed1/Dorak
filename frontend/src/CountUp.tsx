import { useEffect, useRef, useState } from "react";

/**
 * A figure that counts up to its value when it appears, and on to the new one when it changes.
 * Shown at once where motion is unwanted (prefers-reduced-motion) or can't be asked about (tests).
 */
export function CountUp({ value, format = String, duration = 800 }: {
  value: number; format?: (value: number) => string; duration?: number;
}) {
  const still = typeof window.matchMedia !== "function" || window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const [shown, setShown] = useState(still ? value : 0);
  const reached = useRef(still ? value : 0);

  useEffect(() => {
    if (still) {
      setShown(value);
      return;
    }
    const from = reached.current;
    const began = performance.now();
    let frame = 0;
    const tick = (now: number) => {
      const progress = Math.min((now - began) / duration, 1);
      const eased = 1 - Math.pow(1 - progress, 3);
      reached.current = from + (value - from) * eased;
      setShown(reached.current);
      if (progress < 1) {
        frame = requestAnimationFrame(tick);
      }
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [value, duration, still]);

  return <>{format(Number.isInteger(value) ? Math.round(shown) : shown)}</>;
}
