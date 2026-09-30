import { useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import type { NewsItem, NewsResponse } from "../api/types";
import { useMarket } from "./market";

/**
 * News, newest first: loads the latest page, then fetches only newer items whenever
 * the live feed reports a new news id. [ticker] narrows to that company plus
 * market-wide and same-sector news.
 */
export function useNews(ticker?: string, limit = 20) {
  const { market } = useMarket();
  const [items, setItems] = useState<NewsItem[]>();
  const [error, setError] = useState<string>();
  const newest = useRef(0);
  const loading = useRef(false);
  const q = ticker ? `ticker=${encodeURIComponent(ticker)}&` : "";

  useEffect(() => {
    let cancelled = false;
    setItems(undefined);
    newest.current = 0;
    loading.current = true;
    api.get<NewsResponse>(`/api/v1/news?${q}limit=${limit}`).then(
      (r) => {
        if (cancelled) return;
        setItems(r.news);
        newest.current = r.news[0]?.id ?? 0;
      },
      (e: Error) => !cancelled && setError(e.message),
    ).finally(() => { loading.current = false; });
    return () => { cancelled = true; };
  }, [q, limit]);

  const latest = market?.latestNewsId ?? 0;
  useEffect(() => {
    if (items === undefined || loading.current || latest <= newest.current) return;
    loading.current = true;
    api.get<NewsResponse>(`/api/v1/news?${q}after=${newest.current}&limit=${limit}`).then(
      (r) => {
        const first = r.news[0];
        if (first) {
          newest.current = first.id;
          setItems((old) => [...r.news, ...(old ?? [])].slice(0, 100));
        } else {
          newest.current = latest; // nothing for this ticker
        }
      },
      () => {},
    ).finally(() => { loading.current = false; });
  }, [latest, items, q, limit]);

  const loadMore = () => {
    const oldest = items?.[items.length - 1]?.id;
    if (!oldest) return;
    api.get<NewsResponse>(`/api/v1/news?${q}before=${oldest}&limit=${limit}`).then(
      (r) => setItems((old) => [...(old ?? []), ...r.news]),
      () => {},
    );
  };

  return { items, error, loadMore };
}
