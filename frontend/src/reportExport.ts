export const reportSections = [
  { key: 'ledger', label: '日常记账' },
  { key: 'journal', label: '生活日志' },
  { key: 'tasks', label: '全局待办' },
  { key: 'smoking', label: '吸烟记录' },
  { key: 'alcohol', label: '饮酒记录' },
  { key: 'body', label: '身体测量' },
  { key: 'intimacy', label: '私人记录' },
] as const
export type ReportKey = typeof reportSections[number]['key']
export type ReportFormat = 'md' | 'json'
type Values = Record<string, unknown>
export interface PeriodReport {
  from: string
  to: string
  timezone: string
  sections: { key: ReportKey; label: string; rule: string; summary: Values; records: Values[] }[]
}

// Escape user-entered text so notes with pipes, line breaks or HTML stay in their cells.
function markdownCell(value: unknown): string {
  if (value == null) return '—'
  if (Array.isArray(value)) return value.map(markdownCell).join('、')
  if (typeof value === 'boolean') return value ? '是' : '否'
  const text = typeof value === 'object' ? JSON.stringify(value) : String(value)
  return text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replace(/[\\|`*_\[\]~]/g, '\\$&').replace(/\r\n|\r|\n/g, '<br>')
}
export function formatReport(report: PeriodReport, format: ReportFormat) {
  if (format === 'json') return JSON.stringify(report, null, 2) + '\n'
  const lines = [
    '# 自记 · 个人记录', '',
    `日期范围：${report.from} 至 ${report.to}（含首尾两天，${report.timezone}）。`,
    `导出内容：${report.sections.map(section => section.label).join('、')}。`, '',
    '明细中的「—」表示缺少资料或该字段不适用。行为记录中的估算费用与账本的实际收支分别统计。', '',
  ]
  for (const section of report.sections) {
    lines.push(`## ${section.label}`, '', section.rule, '')
    for (const [label, value] of Object.entries(section.summary)) {
      lines.push(`- ${markdownCell(label)}：${markdownCell(value)}`)
    }
    lines.push('', `### 明细（${section.records.length} 条）`, '')
    if (!section.records.length) {
      lines.push('此范围内没有符合条件的记录。', '')
      continue
    }
    // Sessions and individual drinks have different fields and units.
    const tables = new Map<string, Values[]>()
    for (const record of section.records) {
      const name = section.key === 'alcohol' ? String(record['记录类型'] || '饮酒记录') : ''
      if (!tables.has(name)) tables.set(name, [])
      tables.get(name)!.push(record)
    }
    for (const [name, records] of tables) {
      if (name) lines.push(`#### ${markdownCell(name)}`, '')
      const columns = [...new Set(records.flatMap(row => Object.keys(row)))].filter(key => !(name && key === '记录类型'))
      lines.push('| ' + columns.map(markdownCell).join(' | ') + ' |', '| ' + columns.map(() => '---').join(' | ') + ' |')
      for (const row of records) lines.push('| ' + columns.map(key => markdownCell(row[key])).join(' | ') + ' |')
      lines.push('')
    }
  }
  return lines.join('\n')
}
