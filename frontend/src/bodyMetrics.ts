import type { Row } from './types'

export function bodyNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

// BMI always uses height and weight from the same measurement record.
export function calculateBmi(row?: Record<string, unknown>): number | null {
  const height = bodyNumber(row?.heightCm), weight = bodyNumber(row?.weightKg)
  return height != null && height > 0 && weight != null && weight > 0
    ? weight / ((height / 100) ** 2) : null
}

export function formatBodyValue(value: unknown, digits = 1): string {
  const number = bodyNumber(value)
  return number == null ? '—' : number.toLocaleString('zh-CN', { maximumFractionDigits: digits })
}

export function sortBodyRows(rows: Row[]): Row[] {
  // Measurements are maintained by date. Within a day, use entry order so that
  // a newly added date-only measurement is not hidden behind an older clock time.
  return rows.filter(row => !row.deleted).slice().sort((a, b) =>
    String(b.measuredAt).slice(0, 10).localeCompare(String(a.measuredAt).slice(0, 10)) ||
    String(b.createdAt || '').localeCompare(String(a.createdAt || '')) ||
    String(b.measuredAt).localeCompare(String(a.measuredAt)) || b.id.localeCompare(a.id))
}
