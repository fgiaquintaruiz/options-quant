import React, { useState, useEffect, useRef } from 'react'
import { Zap, ChevronUp, ChevronDown, ShieldCheck, ShieldAlert } from 'lucide-react'
import ReplayControls from './ReplayControls'
import SwapButton from './SwapButton'

/**
 * Popover trigger that groups advanced/infrequent scan engine controls:
 * - Concurrent input
 * - Risk% control
 * - Mock Market toggle + Mock Signal button (when mockMarketOpen=true)
 * - ReplayControls (when mockMarketOpen=true)
 *
 * All state and handlers live in the parent (ScanEngineControls → LiveDashboard).
 * This component is purely presentational.
 */
export default function AdvancedScanControls({
  scanning,
  mockMarketOpen,
  maxConcurrent,
  riskInput,
  marketOpen,
  onMaxConcurrentChange,
  onRiskAdjust,
  onToggleMockMarket,
  onInjectMockSignal,
  onReplayActiveChange,
  onError,
}) {
  const [open, setOpen] = useState(false)
  const ref = useRef(null)

  useEffect(() => {
    if (!open) return
    const handler = (e) => {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false)
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [open])

  return (
    <div className="asc-wrap" ref={ref} style={{ position: 'relative' }}>
      <button
        type="button"
        data-testid="advanced-scan-trigger"
        className={`btn btn-sm asc-trigger ${open ? 'asc-trigger--open' : ''}`}
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-haspopup="true"
        title="Advanced scan settings"
      >
        ⚙ Advanced {open ? '▲' : '▼'}
      </button>

      {open && (
        <div
          className="asc-dropdown"
          data-testid="advanced-scan-dropdown"
          role="dialog"
          aria-label="Advanced scan controls"
        >

          {/* Concurrent */}
          <div className="asc-row">
            <span className="stat-label-sm color-muted">Concurrent:</span>
            <input
              type="number"
              min="1"
              max="16"
              value={maxConcurrent}
              onChange={(e) => onMaxConcurrentChange(e.target.value)}
              disabled={scanning}
              className="ld-concurrent-input"
            />
          </div>

          <div className="asc-divider" />

          {/* Risk% */}
          <div className="asc-row">
            <span className="stat-label-sm color-muted">Risk%:</span>
            <strong className="color-text ld-risk-val">{riskInput}</strong>
            <div className="flex-col gap-0">
              <button
                type="button"
                data-testid="live-risk-up"
                className="btn-icon color-muted"
                onClick={() => onRiskAdjust(1.0)}
              >
                <ChevronUp size={12} />
              </button>
              <button
                type="button"
                data-testid="live-risk-down"
                className="btn-icon color-muted"
                onClick={() => onRiskAdjust(-1.0)}
              >
                <ChevronDown size={12} />
              </button>
            </div>
          </div>

          <div className="asc-divider" />

          {/* Mock Market */}
          <div className="asc-row">
            <SwapButton
              active={mockMarketOpen}
              onText="Mock Mkt"
              offText="Real Mkt"
              onClick={onToggleMockMarket}
              activeColor="#f0883e"
              offColor="#3fb950"
              icon={mockMarketOpen ? ShieldAlert : ShieldCheck}
              testId="live-toggle-mock-market"
            />
          </div>

          {mockMarketOpen && (
            <>
              <div className="asc-row">
                <button
                  className="btn ld-mock-btn"
                  onClick={onInjectMockSignal}
                  data-testid="live-inject-mock-signal"
                >
                  <Zap size={14} /> Mock Signal
                </button>
              </div>
              <div className="asc-divider" />
              <div className="asc-row asc-row--block">
                <ReplayControls
                  marketOpen={marketOpen}
                  mockMarket={mockMarketOpen}
                  onActiveChange={onReplayActiveChange}
                  onError={onError}
                />
              </div>
            </>
          )}

        </div>
      )}
    </div>
  )
}
