import { useEffect, useRef, useState, type ReactNode } from "react";

/** Briefly tints its content green/red when [value] rises/falls. */
export function Flash({ value, children }: { value: number | null | undefined; children: ReactNode }) {
  const prev = useRef(value);
  const [cls, setCls] = useState("");
  useEffect(() => {
    if (value != null && prev.current != null && value !== prev.current) {
      setCls(value > prev.current ? "flash-up" : "flash-down");
      const t = setTimeout(() => setCls(""), 900);
      prev.current = value;
      return () => clearTimeout(t);
    }
    prev.current = value;
  }, [value]);
  return <span className={cls} style={{ borderRadius: 3, padding: "0 2px" }}>{children}</span>;
}
