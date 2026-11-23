import type { Row } from './types'

export type Direction = 'EXPENSE' | 'INCOME'
export type LedgerRow = Row & { date: string; direction: Direction; amount: number; category: string; notes?: string }
export type DirectionFilter = Direction | 'ALL'

function cents(row: LedgerRow) {
  return Math.round(Number(row.amount) * 100)
}

export type LedgerPeriod = 'day' | 'month' | 'year'
const keyLength = { day: 10, month: 7, year: 4 }

export function periodKey(date: string, period: LedgerPeriod) {
  return date.slice(0, keyLength[period])
}

function shiftDays(date: string, days: number) {
  const result = new Date(date + 'T12:00:00Z')
  result.setUTCDate(result.getUTCDate() + days)
  if (result.getUTCFullYear() < 1000 || result.getUTCFullYear() > 9999) return null
  return result.toISOString().slice(0, 10)
}

function daysInMonth(month: string) {
  const [year, number] = month.split('-').map(Number)
  return new Date(Date.UTC(year, number, 0)).getUTCDate()
}

export function anchorInMonth(month: string, anchor: string) {
  const day = Math.min(Number(anchor.slice(8)), daysInMonth(month))
  return month + '-' + String(day).padStart(2, '0')
}

export function adjacentPeriod(date: string, period: LedgerPeriod, step: 1 | -1) {
  if (period === 'day') return shiftDays(date, step)
  const next = new Date(date.slice(0, 7) + '-01T12:00:00Z')
  next.setUTCMonth(next.getUTCMonth() + step * (period === 'year' ? 12 : 1))
  if (next.getUTCFullYear() < 1000 || next.getUTCFullYear() > 9999) return null
  return anchorInMonth(next.toISOString().slice(0, 7), date)
}

export function weekColumn(date: string) {
  return (new Date(date + 'T12:00:00Z').getUTCDay() + 6) % 7 + 1
}

export function periodHistory(rows: LedgerRow[], date: string, period: LedgerPeriod) {
  const month = date.slice(0, 7)
  let keys: string[]
  if (period === 'year') {
    keys = Array.from({length: 12}, (_, i) => date.slice(0, 4) + '-' + String(i + 1).padStart(2, '0'))
  } else if (period === 'month') {
    keys = Array.from({length: daysInMonth(month)}, (_, i) => month + '-' + String(i + 1).padStart(2, '0'))
  } else {
    const firstOffset = 1 - weekColumn(date)
    keys = Array.from({length: 7}, (_, i) => shiftDays(date, firstOffset + i)).filter((key): key is string => key !== null)
  }
  const buckets = keys.map(key => ({ key, expense: 0, count: 0 }))
  const byKey = new Map(buckets.map(item => [item.key, item]))
  for (const row of rows) {
    const item = byKey.get(period === 'year' ? row.date.slice(0, 7) : row.date)
    if (!item) continue
    item.count++
    if (row.direction === 'EXPENSE') item.expense += cents(row)
  }
  return buckets
}

export function cashflow(rows: LedgerRow[]) {
  let income = 0, expense = 0
  for (const row of rows) {
    if (row.direction === 'INCOME') income += cents(row)
    else expense += cents(row)
  }
  return { income, expense, net: income - expense }
}

export function categoryRanking(rows: LedgerRow[], direction: Direction) {
  const grouped = new Map<string, number>()
  for (const row of rows) {
    if (row.direction === direction) grouped.set(row.category, (grouped.get(row.category) || 0) + cents(row))
  }
  return [...grouped].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0], 'zh-CN'))
}

export function matchingRows(rows: LedgerRow[], query: string, direction: DirectionFilter, category: string) {
  const words = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return rows.filter(row => {
    if (direction !== 'ALL' && row.direction !== direction) return false
    if (category && row.category !== category) return false
    const text = [row.category, row.notes, row.date, Number(row.amount).toFixed(2)].join(' ').toLocaleLowerCase()
    return words.every(word => text.includes(word))
  }).sort((a, b) => b.date.localeCompare(a.date) || String(b.createdAt).localeCompare(String(a.createdAt)) || b.id.localeCompare(a.id))
}

export function dayGroups(rows: LedgerRow[]) {
  const byDate = new Map<string, LedgerRow[]>()
  for (const row of rows) {
    const group = byDate.get(row.date)
    if (group) group.push(row)
    else byDate.set(row.date, [row])
  }
  return [...byDate].map(([date, entries]) => ({ date, entries, ...cashflow(entries) }))
}
