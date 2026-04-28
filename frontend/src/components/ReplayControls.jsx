import React, { useState, useEffect, useRef } from 'react'
import { Play, Square } from 'lucide-react'
import { replayApi } from '../api'
import { LS } from '../utils/storage'

const SPEEDS = [30, 60, 180, 360]

function todayStr()     { return new Date().toISOString().split('T')[0] }
function yesterdayStr() { return new Date(Date.now() - 86_400_000).toISOString().split('T')[0] }

export default function ReplayControls({ marketOpen, onActiveChange, onError }) {
  const today     = todayStr()
  const yesterday = yesterdayStr()

  const [replayDate,   setReplayDate]   = useState(() => LS.get('replay_lastDate', yesterday))
  const [replaySpeed,  setReplaySpeed]  = useState(30)
  const [replayActive, setReplayActive] = useState(false)
  const lastActiveRef = useRef(false)

  useEffect(() => {
    let cancelled = false
    const tick = async () => {
      try {
        const data = await replayApi.status()
        if (cancelled) return
        const isActive = !!data?.active
        if (isActive !== lastActiveRef.current) {
          lastActiveRef.current = isActive
          setReplayActive(isActive)
          onActiveChange?.(isActive)
        }
      } catch (e) {
        console.warn('[replay-controls] status poll:', e.message)
      }
    }
    tick()
    const id = setInterval(tick, 2000)
    return () => { cancelled = true; clearInterval(id) }
  }, [onActiveChange])

  const cycleSpeed = () => setReplaySpeed(s => SPEEDS[(SPEEDS.indexOf(s) + 1) % SPEEDS.length])

  const handleStart = async () => {
    try {
      await replayApi.start(replayDate, replaySpeed)
      LS.set('replay_lastDate', replayDate)
      setReplayActive(true)
      lastActiveRef.current = true
      onActiveChange?.(true)
    } catch (e) {
      onError?.(e.message || 'Replay blocked during market hours')
    }
  }

  const handleStop = async () => {
    await replayApi.stop()
    setReplayActive(false)
    lastActiveRef.current = false
    onActiveChange?.(false)
  }

  return (
    <>
      <input
        type="date"
        className="ld-replay-date"
        data-testid="replay-date-input"
        value={replayDate}
        max={today}
        onChange={e => setReplayDate(e.target.value)}
        disabled={replayActive}
      />
      <button className="btn ld-speed-btn" data-testid={`replay-speed-btn-${replaySpeed}`} onClick={cycleSpeed} disabled={replayActive}>
        {replaySpeed}x
      </button>
      <button
        className={`btn ${replayActive ? 'btn-secondary' : 'btn-primary'} ld-replay-start`}
        data-testid="replay-start-btn"
        onClick={replayActive ? handleStop : handleStart}
        disabled={marketOpen && !replayActive}
        title={marketOpen && !replayActive ? 'Not available during market hours' : undefined}
      >
        {replayActive ? <Square size={14}/> : <Play size={14}/>}
      </button>
    </>
  )
}
