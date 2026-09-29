import {
  CandlestickSeries,
  ColorType,
  createChart,
  CrosshairMode,
  HistogramSeries,
  LineSeries,
  TickMarkType,
  type IChartApi,
  type ISeriesApi,
  type Time,
  type UTCTimestamp,
} from "lightweight-charts";
import { useEffect, useRef, useState } from "react";
import type { Candle, Quote, SessionInfo } from "../api/types";
import { compact, price } from "../lib/format";
import type { Series } from "../lib/ranges";
import { cssVar, useResolvedTheme } from "../lib/theme";

const ET = "America/New_York";
const toTime = (iso: string) => Math.floor(Date.parse(iso) / 1000) as UTCTimestamp;
const fmt = (t: number, o: Intl.DateTimeFormatOptions) => new Date(t * 1000).toLocaleString("en-US", { timeZone: ET, ...o });

type Bar = { time: UTCTimestamp; open: number; high: number; low: number; close: number; volume: number };

function toBar(c: Candle): Bar {
  return { time: toTime(c.time), open: c.open / 100, high: c.high / 100, low: c.low / 100, close: c.close / 100, volume: c.volume };
}

interface Props {
  series?: Series;
  kind: "candle" | "line";
  quote?: Quote;
  session?: SessionInfo;
  /** Server time of the latest feed frame. */
  frameTime?: string;
}

/**
 * Price chart with volume in its own pane below (one axis per pane; never a dual
 * axis). Up candles are hollow and down candles filled, so direction survives
 * without color. Crosshair legend shows OHLCV for the hovered bar.
 */
export function PriceChart({ series, kind, quote, session, frameTime }: Props) {
  const box = useRef<HTMLDivElement>(null);
  const chart = useRef<IChartApi>(null);
  const price$ = useRef<ISeriesApi<"Candlestick"> | ISeriesApi<"Line">>(null);
  const volume$ = useRef<ISeriesApi<"Histogram">>(null);
  const last = useRef<{ bar?: Bar; dayVolume?: number }>({});
  const theme = useResolvedTheme();
  const [legend, setLegend] = useState<Bar>();

  const intraday = (series?.bucket ?? 0) > 0;

  // Create / rebuild the chart when the theme, kind or data change.
  useEffect(() => {
    if (!box.current || !series) return;
    const up = cssVar("--up"), down = cssVar("--down"), surface = cssVar("--surface");
    const c = createChart(box.current, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: surface },
        textColor: cssVar("--text-2"),
        fontFamily: getComputedStyle(document.body).fontFamily,
        attributionLogo: true,
        panes: { separatorColor: cssVar("--border"), enableResize: false },
      },
      grid: { vertLines: { color: cssVar("--grid") }, horzLines: { color: cssVar("--grid") } },
      rightPriceScale: { borderColor: cssVar("--border") },
      timeScale: {
        borderColor: cssVar("--border"),
        timeVisible: intraday,
        secondsVisible: false,
        tickMarkFormatter: (t: Time, type: TickMarkType) => {
          const n = t as number;
          if (type === TickMarkType.Year) return fmt(n, { year: "numeric" });
          if (type === TickMarkType.Month) return fmt(n, { month: "short" });
          if (type === TickMarkType.DayOfMonth || !intraday) return fmt(n, { month: "short", day: "numeric" });
          return fmt(n, { hour: "numeric", minute: "2-digit" });
        },
      },
      localization: {
        timeFormatter: (t: Time) =>
          fmt(t as number, intraday ? { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" } : { month: "short", day: "numeric", year: "numeric", hour: "numeric" }),
      },
      crosshair: { mode: CrosshairMode.Normal },
    });

    const bars = series.candles.map(toBar);
    const firstBar = bars[0], lastBar = bars[bars.length - 1];
    const rising = !firstBar || !lastBar || lastBar.close >= firstBar.open;
    const p = kind === "candle"
      ? c.addSeries(CandlestickSeries, {
          priceFormat: { type: "price", precision: 2, minMove: 0.01 },
          upColor: surface, borderUpColor: up, wickUpColor: up,
          downColor: down, borderDownColor: down, wickDownColor: down,
        })
      : c.addSeries(LineSeries, { color: rising ? up : down, lineWidth: 2, priceLineVisible: false, priceFormat: { type: "price", precision: 2, minMove: 0.01 } });
    const v = c.addSeries(HistogramSeries, { priceFormat: { type: "volume" }, priceLineVisible: false, lastValueVisible: false }, 1);
    c.panes()[1]?.setHeight(80);

    if (kind === "candle") (p as ISeriesApi<"Candlestick">).setData(bars);
    else (p as ISeriesApi<"Line">).setData(bars.map((b) => ({ time: b.time, value: b.close })));
    v.setData(bars.map((b) => ({ time: b.time, value: b.volume, color: b.close >= b.open ? cssVar("--up-fill") : cssVar("--down-fill") })));
    c.timeScale().fitContent();

    c.subscribeCrosshairMove((param) => {
      const d = param.seriesData.get(p) as { open?: number; high?: number; low?: number; close?: number; value?: number } | undefined;
      const vol = param.seriesData.get(v) as { value?: number } | undefined;
      if (!param.time || !d) return setLegend(undefined);
      const close = d.close ?? d.value ?? 0;
      setLegend({ time: param.time as UTCTimestamp, open: d.open ?? close, high: d.high ?? close, low: d.low ?? close, close, volume: vol?.value ?? 0 });
    });

    chart.current = c;
    price$.current = p;
    volume$.current = v;
    last.current = { bar: lastBar, dayVolume: undefined };
    return () => {
      c.remove();
      chart.current = null;
    };
  }, [series, kind, theme, intraday]);

  // Live updates from the feed.
  useEffect(() => {
    const p = price$.current, v = volume$.current;
    if (!p || !v || !series || !quote || !frameTime || session?.state !== "OPEN") return;
    let bar: Bar;
    if (series.bucket === 0) {
      if (!session.opensAt || quote.open == null) return;
      bar = { time: toTime(session.opensAt), open: quote.open / 100, high: (quote.high ?? quote.last) / 100, low: (quote.low ?? quote.last) / 100, close: quote.last / 100, volume: quote.volume };
    } else {
      const t = (Math.floor(Date.parse(frameTime) / 1000 / series.bucket) * series.bucket) as UTCTimestamp;
      const prev = last.current.bar;
      const dv = last.current.dayVolume == null ? 0 : Math.max(0, quote.volume - last.current.dayVolume);
      const px = quote.last / 100;
      bar = prev && prev.time === t
        ? { ...prev, high: Math.max(prev.high, px), low: Math.min(prev.low, px), close: px, volume: prev.volume + dv }
        : { time: t, open: px, high: px, low: px, close: px, volume: dv };
      if (prev && t < prev.time) return; // stale frame
    }
    last.current = { bar, dayVolume: quote.volume };
    if (kind === "candle") (p as ISeriesApi<"Candlestick">).update(bar);
    else (p as ISeriesApi<"Line">).update({ time: bar.time, value: bar.close });
    v.update({ time: bar.time, value: bar.volume, color: bar.close >= bar.open ? cssVar("--up-fill") : cssVar("--down-fill") });
  }, [quote, frameTime, session, series, kind]);

  const shown = legend ?? last.current.bar;
  return (
    <div className="chart-box" ref={box} role="img" aria-label="Price chart">
      {shown && (
        <div className="chart-legend num" aria-hidden="true">
          <span>{fmt(shown.time, intraday ? { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" } : { month: "short", day: "numeric", year: "numeric" })}</span>
          <span>O <b>{price(shown.open * 100)}</b></span>
          <span>H <b>{price(shown.high * 100)}</b></span>
          <span>L <b>{price(shown.low * 100)}</b></span>
          <span>C <b>{price(shown.close * 100)}</b></span>
          <span>Vol <b>{compact(shown.volume)}</b></span>
        </div>
      )}
      {series && series.candles.length === 0 && <div className="empty">No data for this range yet.</div>}
    </div>
  );
}
