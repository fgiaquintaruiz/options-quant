import React, { useState, useEffect } from 'react'
import { Save, Plus, Trash2, List } from 'lucide-react'
import { LS } from '../utils/storage'

const DEFAULT_GROUPS = [
  { id: 'mega-tech', label: 'Mega Tech', tickers: 'AAPL,MSFT,NVDA,GOOGL,AMZN,META,TSLA,AMD,AVGO,ORCL' },
  { id: 'spy-top-10', label: 'SPY Top 10', tickers: 'AAPL,MSFT,NVDA,AMZN,META,GOOGL,BRK B,GOOG,TSLA,AVGO' },
  { id: 'semis', label: 'Semis', tickers: 'NVDA,AMD,AVGO,INTC,TSM,ASML,QCOM,MU,AMAT,LRCX' },
  { id: 'finance', label: 'Finance', tickers: 'JPM,V,MA,BAC,MS,GS,HSBC,AXP,PYPL,COIN' },
  { id: 'crypto', label: 'Crypto Proxy', tickers: 'COIN,MARA,RIOT,MSTR,CLSK,MHT,WULF,BTBT' },
  { id: 'spy', label: 'SPY', tickers: 'SPY' },
  { id: 'qqq', label: 'QQQ', tickers: 'QQQ' }
]

export default function SettingsPage() {
  const [groups, setGroups] = useState(() => LS.get('ticker_groups', DEFAULT_GROUPS))
  const [newGroup, setNewGroup] = useState({ label: '', tickers: '' })
  const [saveStatus, setSaveStatus] = useState(null)

  useEffect(() => {
    LS.set('ticker_groups', groups)
  }, [groups])

  const handleAddGroup = () => {
    if (!newGroup.label || !newGroup.tickers) return
    const id = newGroup.label.toLowerCase().replace(/\s+/g, '-')
    setGroups([...groups, { ...newGroup, id }])
    setNewGroup({ label: '', tickers: '' })
  }

  const handleRemoveGroup = (id) => {
    setGroups(groups.filter(g => g.id !== id))
  }

  const handleSave = () => {
    LS.set('ticker_groups', groups)
    setSaveStatus('Settings saved successfully!')
    setTimeout(() => setSaveStatus(null), 3000)
  }

  return (
    <div className="flex-col gap-20" style={{ maxWidth: 800, margin: '0 auto' }}>
      <div className="card">
        <div className="flex-between mb-20">
          <h3 className="m-0 flex-align-center gap-8">
            <List size={20} className="color-info" />
            Ticker List Management
          </h3>
          <button className="btn btn-primary" onClick={handleSave}>
            <Save size={16} /> Save Changes
          </button>
        </div>

        {saveStatus && (
          <div className="badge badge-success mb-12" style={{ width: '100%', padding: '10px', textAlign: 'center' }}>
            {saveStatus}
          </div>
        )}

        <div className="flex-col gap-15">
          {groups.map((group) => (
            <div key={group.id} className="bg-card border-main rounded-md p-12-20 flex-between">
              <div className="flex-col gap-4" style={{ flex: 1 }}>
                <span className="font-bold color-text">{group.label}</span>
                <span className="text-xs color-muted" style={{ wordBreak: 'break-all' }}>{group.tickers}</span>
              </div>
              <button 
                className="btn color-error" 
                onClick={() => handleRemoveGroup(group.id)}
                style={{ background: 'none', padding: '8px' }}
              >
                <Trash2 size={18} />
              </button>
            </div>
          ))}
        </div>

        <div className="divider-v w-full" style={{ margin: '25px 0', height: '1px' }} />

        <h4 className="mb-12 color-muted text-uppercase text-xs font-bold">Add New Group</h4>
        <div className="grid gap-12" style={{ gridTemplateColumns: '1fr 2fr auto' }}>
          <input 
            type="text" 
            placeholder="Group Name" 
            className="ticker-input"
            value={newGroup.label}
            onChange={e => setNewGroup({ ...newGroup, label: e.target.value })}
          />
          <input 
            type="text" 
            placeholder="Tickers (comma separated)" 
            className="ticker-input"
            value={newGroup.tickers}
            onChange={e => setNewGroup({ ...newGroup, tickers: e.target.value.toUpperCase() })}
          />
          <button className="btn btn-primary" onClick={handleAddGroup} disabled={!newGroup.label || !newGroup.tickers}>
            <Plus size={18} />
          </button>
        </div>
      </div>
      
      <div className="card">
        <h3>Platform Preferences</h3>
        <p className="color-muted text-sm">More settings coming soon (Notification webhooks, default risk profiles, etc.)</p>
      </div>
    </div>
  )
}
