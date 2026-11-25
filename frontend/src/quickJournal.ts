export type QuickDraft = {
  date: string
  period: string
  title: string
  category: string
  people: string[]
  place: string
  tags: string[]
  amount: string
  expenseCategory: string
  notes: string
  paymentHint: string
}

const periods: [RegExp, string][] = [
  [/凌晨/, 'DAWN'], [/清晨|早晨|早上|今早|昨早|早餐|早饭/, 'EARLY'],
  [/上午/, 'MORNING'], [/中午|午间|午餐|午饭/, 'NOON'],
  [/下午/, 'AFTERNOON'], [/晚上|傍晚|夜里|今夜|昨夜|昨晚|前晚|今晚|晚餐|晚饭/, 'EVENING'],
]
const activities: [RegExp, string, string][] = [
  [/吃|餐厅|聚餐|火锅|烧烤|咖啡|早餐|午餐|晚餐|食堂/, 'MEAL', '餐饮'],
  [/出差|高铁|火车|机票|打车|地铁|公交|车票/, 'TRAVEL', '交通'],
  [/上课|学习|复习|课程|讲座|自习|教材/, 'STUDY', '学习'],
  [/工作|开会|会议|办公|项目|加班/, 'WORK', '其他'],
  [/电影|聚会|爬山|打球|游玩|逛展/, 'SOCIAL', '休闲'],
  [/买|购物|快递/, 'LIFE', '购物'],
]

function offsetDay(date: string, offset: number) {
  const day = new Date(date + 'T12:00:00Z')
  day.setUTCDate(day.getUTCDate() + offset)
  return day.toISOString().slice(0, 10)
}

function readDate(text: string, today: string, fallback: string) {
  const normalized = text.replace(/(\d{4})\.(\d{1,2})\.(\d{1,2})/g, '$1-$2-$3')
  const calendar = normalized.match(/(?:^|[^\d])(?:(\d{4})\s*(?:年|[-/])\s*)?(\d{1,2})\s*(?:月|[-/])\s*(\d{1,2})(?:日|号)?(?!\d)/)
  if (calendar) {
    let year = Number(calendar[1] || today.slice(0, 4))
    const monthDay = calendar[2].padStart(2, '0') + '-' + calendar[3].padStart(2, '0')
    if (!calendar[1] && year + '-' + monthDay > today) year--
    const date = year + '-' + monthDay
    const parsed = new Date(date + 'T12:00:00Z')
    return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === date ? date : ''
  }
  if (/大前天/.test(text)) return offsetDay(today, -3)
  if (/前天|前晚/.test(text)) return offsetDay(today, -2)
  if (/昨天|昨日|昨晚|昨夜|昨早/.test(text)) return offsetDay(today, -1)
  if (/今天|今日|今早|今晚|今夜/.test(text)) return today
  const weekday = text.match(/(上上|上|本|这|下)?(?:周|星期|礼拜)([一二三四五六日天])/)
  if (weekday) {
    const current = new Date(today + 'T12:00:00Z').getUTCDay() || 7
    const target = '一二三四五六日'.indexOf(weekday[2].replace('天','日')) + 1
    const weeks = weekday[1] === '上上' ? -2 : weekday[1] === '上' ? -1 : weekday[1] === '下' ? 1
      : !weekday[1] && target > current ? -1 : 0
    return offsetDay(today, target - current + weeks * 7)
  }
  if (/后天|明天|明晚|明早|下周|上周|星期|周[一二三四五六日天末]/.test(text)) return ''
  return fallback
}

function payment(text: string) {
  if (/还款|偿还|归还(?:本金|欠款)|还(?:了|清)?(?:花呗|白条|月付|贷款|房贷|欠款|信用卡|借款)/.test(text))
    return {amount:'', paymentHint:'这句话提到还款。请在日常记账里选「还款」，拆分本金和息费。'}
  // Only an explicit first-person payment is sufficient to prefill the ledger.
  // Requiring a boundary after the amount also excludes foreign currencies.
  const personal = /(?:我(?:自己|本人)?\s*(?:实际支付|实付|支付|付款|付|花|出)|本人\s*(?:实付|支付|付))\s*了?\s*([¥￥]?)\s*(\d+(?:[,，]\d+)*(?:\.\d+)?)\s*(元(?:人民币)?|块(?:钱)?|人民币)?(?=\s*(?:$|[。；;！!]|[,，](?!\s*\d)))/g
  const amounts: string[] = []
  for (const match of text.matchAll(personal)) {
    const clause = text.slice(0, match.index).split(/[，,。；;！!？?]/).at(-1) || ''
    if (/(?:帮|替|给|让|叫|要|如果|假如|可能|好像|以为|以为是|不是|并非|并不是|原本|本来)\s*$/.test(clause)) continue
    if (match[0].includes('花') && !match[1] && !match[3]) continue
    if (!/^(?:\d+|\d{1,3}(?:[,，]\d{3})+)(?:\.\d{1,2})?$/.test(match[2])) continue
    const digits = match[2].replace(/[,，]/g, '')
    const amount = Number(digits)
    if (amount > 0 && amount <= 999_999_999) amounts.push(digits)
  }
  if (amounts.length === 1) return { amount: amounts[0], paymentHint: '已识别本人付款，请核对金额。' }
  if (amounts.length > 1) return { amount: '', paymentHint: '句中有多笔本人付款，请核对本次记入的金额。' }
  if (/[¥￥]|\d\s*(?:元|块)|实付|付款|支付|一共|总价|人均|AA/i.test(text))
    return { amount: '', paymentHint: '请核对自己实际付了多少，再勾选支出。' }
  return { amount: '', paymentHint: '' }
}

function peopleIn(text: string, candidates: string[]) {
  const result: string[] = []
  let remaining = text
  for (const name of [...candidates].sort((a, b) => b.length - a.length)) {
    if (!name || !remaining.includes(name)) continue
    result.push(name)
    remaining = remaining.replaceAll(name, ' ')
  }
  return result.sort((a,b) => text.indexOf(a) - text.indexOf(b))
}

function placeIn(text: string, places: string[]) {
  const phrase = /(?:在|到了|前往|去了?)([^，,。；;\n！？!?]{1,60})/.exec(text)?.[1]?.trim()
  if (!phrase) return ''
  const known = [...places].sort((a,b) => b.length - a.length).find(name => name && phrase.startsWith(name))
  if (known) return known
  return phrase.match(/^(.{1,40}?)(?=\s*(?:一起|吃|喝|聚餐|上课|开会|学习|工作|自习|买|看电影|打球|见面|和|跟|出差))/)?.[1].trim() || ''
}

export function parseQuickJournal(text: string, today: string, fallback: string, people: string[], places: string[]): QuickDraft {
  const line = text.trim()
  const activity = activities.find(([pattern]) => pattern.test(line))
  const tags = [...new Set(Array.from(line.matchAll(/(?:^|\s|[，,；;])#([^#\s，,。；;！？!?]{1,80})(?=$|\s|[，,。；;！？!?])/g), m => m[1].toLowerCase()))]
  return {
    title: line,
    date: readDate(line, today, fallback),
    period: periods.find(([pattern]) => pattern.test(line))?.[1] || '',
    category: activity?.[1] || 'LIFE',
    expenseCategory: activity?.[2] || '其他',
    place: placeIn(line, places),
    people: peopleIn(line, people),
    tags,
    notes: line,
    ...payment(line),
  }
}
