# Product Vision (personal use)

Living one-page document: priorities and boundaries. Update whenever the focus changes.

## Problem it solves

Assisted trading engine against **Interactive Brokers (TWS/Gateway)**: scan tickers, detect signals, manage risk, run backtests, and serve a **SPA** (live / backtest / health) from Spring on `:9090`.

## What it is NOT (for now)

- Not a multi-tenant product or SaaS service.
- Does not replace human judgment or due diligence; losses are the operator's responsibility.
- Does not guarantee profitability or market data beyond what IBKR and the code itself deliver.

## Operating modes

| Mode | Description |
|------|-------------|
| **Paper / sim** | TWS paper port and account; validate flows without real money. |
| **Live** | Only when configuration, risk, and limits have been explicitly reviewed. |

The source of truth for host/port/account is `application.yml` (and whatever TWS shows).

## Definition of "good enough" (personal criteria)

Check or rewrite as the project evolves:

- [ ] Predictable local startup: backend + packaged frontend or `npm run dev` documented.
- [ ] Live UI: view scan status, signals, and controls without blocking errors.
- [ ] Backtest UI: launch / stop / view progress or results as implemented.
- [ ] IBKR connection: clear behavior **with TWS off** (controlled errors) and **with TWS on** (data or orders per configuration).
- [ ] Automated tests I trust to avoid breaking critical paths when changing code.

## Technical decisions worth documenting separately

If a choice is costly to undo (e.g. order model, IBKR retries, risk limits), a short **ADR** in `.atl/` or `docs/adr/` is enough; no formal RFC needed.

## Last reviewed

Date: (fill in when editing)
