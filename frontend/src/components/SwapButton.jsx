import React from 'react'

function SwapButton({ active, onText, offText, onClick, activeColor = '#f0883e', offColor = '#238636', icon: Icon, testId }) {
  return (
    <button
      type="button"
      data-testid={testId}
      onClick={onClick}
      className="btn"
      style={{
        padding: '6px 12px',
        fontSize: 12,
        background: active ? `${activeColor}22` : `${offColor}22`,
        border: `1px solid ${active ? `${activeColor}66` : `${offColor}66`}`,
        color: active ? activeColor : offColor,
        minWidth: 110,
        justifyContent: 'center'
      }}
    >
      {Icon && <Icon size={14} />}
      {active ? onText : offText}
    </button>
  )
}

export default SwapButton
