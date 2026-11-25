import type { Row } from './types'

export type Direction = 'EXPENSE' | 'INCOME'
export type DebtKind = 'CREDIT' | 'REPAYMENT' | 'OPENING'
export type LedgerRow = Row & { date: string; direction: Direction; amount: number; category: string; notes?: string;
  debtKind?: DebtKind | null; debtPlatform?: string | null; debtFee?: number | null }
export type DirectionFilter = Direction | 'ALL' | 'DEBT'
export const debtLabels: Record<DebtKind, string> = { CREDIT:'信用消费', REPAYMENT:'还款', OPENING:'期初欠款' }
export const platformPresets = ['美团', '支付宝', '抖音', '京东']

export function cents(amount: number | string | null | undefined) {
  return Math.round(Number(amount || 0) * 100)
}

export function expenseCents(row: LedgerRow) {
  if (row.direction === 'INCOME' || row.debtKind === 'OPENING') return 0
  return cents(row.debtKind === 'REPAYMENT' ? row.debtFee : row.amount)
}

export function debtDelta(row: LedgerRow) {
  if (row.debtKind === 'REPAYMENT') return cents(row.debtFee) - cents(row.amount)
  return row.debtKind === 'CREDIT' || row.debtKind === 'OPENING' ? cents(row.amount) : 0
}

export function debtAccounts(rows: LedgerRow[], through: string) {
  const accounts = new Map<string, {platform: string; balance: number; opening?: LedgerRow}>()
  for (const row of rows) {
    if (row.deleted || !row.debtKind || !row.debtPlatform || row.date > through) continue
    const account = accounts.get(row.debtPlatform) ?? {platform: row.debtPlatform, balance: 0, opening: undefined}
    account.balance += debtDelta(row)
    if (row.debtKind === 'OPENING') account.opening = row
    accounts.set(row.debtPlatform, account)
  }
  return [...accounts.values()].sort((a,b) => b.balance-a.balance || a.platform.localeCompare(b.platform,'zh-CN'))
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
    item.expense += expenseCents(row)
  }
  return buckets
}

export function cashflow(rows: LedgerRow[]) {
  let income = 0, expense = 0, paidPrincipal = 0, cashOut = 0
  for (const row of rows) {
    if (row.direction === 'INCOME') income += cents(row.amount)
    else {
      expense += expenseCents(row)
      if (row.debtKind !== 'CREDIT' && row.debtKind !== 'OPENING') cashOut += cents(row.amount)
      if (row.debtKind === 'REPAYMENT') paidPrincipal -= debtDelta(row)
    }
  }
  return { income, expense, net: income - expense, paidPrincipal, cashOut }
}

export function categoryRanking(rows: LedgerRow[], direction: Direction) {
  const grouped = new Map<string, number>()
  for (const row of rows) {
    if (row.direction !== direction) continue
    const amount = direction === 'EXPENSE' ? expenseCents(row) : cents(row.amount)
    if (amount) grouped.set(row.category, (grouped.get(row.category) || 0) + amount)
  }
  return [...grouped].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0], 'zh-CN'))
}

export function matchingRows(rows: LedgerRow[], query: string, direction: DirectionFilter, category: string) {
  const words = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return rows.filter(row => {
    if (direction === 'DEBT' && !row.debtKind) return false
    if (direction === 'INCOME' && row.direction !== 'INCOME') return false
    if (direction === 'EXPENSE' && !expenseCents(row)) return false
    if (category && row.category !== category) return false
    const text = [row.category, row.notes, row.date, row.debtPlatform, row.debtKind && debtLabels[row.debtKind], Number(row.amount).toFixed(2)].join(' ').toLocaleLowerCase()
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
