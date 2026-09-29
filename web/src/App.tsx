import { useEffect, useState } from "react";
import { BrowserRouter, Link, Route, Routes } from "react-router-dom";
import { api } from "./api/client";
import type { MeResponse } from "./api/types";
import { Header } from "./components/Header";
import { MarketProvider } from "./lib/market";
import { Account } from "./pages/Account";
import { Home } from "./pages/Home";
import { MarginBanner, Toasts } from "./components/Notices";
import { PortfolioPage } from "./pages/Portfolio";
import { Login } from "./pages/Login";
import { QuotePage } from "./pages/Quote";
import { Screener } from "./pages/Screener";

export function App() {
  const [state, setState] = useState<"loading" | "in" | "out">("loading");
  const [email, setEmail] = useState<string>();

  useEffect(() => {
    api.restore().then((ok) => setState(ok ? "in" : "out"));
    return api.onSignedOut(() => setState("out"));
  }, []);

  useEffect(() => {
    if (state === "in") api.get<MeResponse>("/api/v1/me").then((m) => setEmail(m.email), () => undefined);
  }, [state]);

  if (state === "loading") return null;
  if (state === "out") return <Login onSignedIn={() => setState("in")} />;

  return (
    <BrowserRouter>
      <MarketProvider>
        <Header email={email} onSignOut={() => void api.signOut()} />
        <MarginBanner />
        <main>
          <Routes>
            <Route path="/portfolio" element={<PortfolioPage />} />
            <Route path="/" element={<Home />} />
            <Route path="/quote/:ticker" element={<QuotePage />} />
            <Route path="/screener" element={<Screener />} />
            <Route path="/account" element={<Account />} />
            <Route path="*" element={<div className="empty">Page not found. <Link to="/">Back to markets</Link></div>} />
          </Routes>
        </main>
        <Toasts />
        <footer className="footer">
          Stonks is a game. All companies, prices and money are fictional. Charts by{" "}
          <a href="https://www.tradingview.com/" target="_blank" rel="noreferrer">TradingView</a>.
        </footer>
      </MarketProvider>
    </BrowserRouter>
  );
}
