export type Scene = 'weekday' | 'meal' | 'trip' | 'work' | 'study' | 'tag' | 'person'
export type Measure = 'alcohol' | 'smoking'
export type JournalSpan = {
  id: string; title: string; category: string; startDate: string; endDate: string
  people: string[]; tags: string[]
}
export type DailyBehavior = {
  date: string; isFuture?: boolean; hasAlcoholRecords: boolean; hasSmokingRecords: boolean
  alcoholSessions: number; smokingCount: number
}
export const weekdays = ['周一','周二','周三','周四','周五','周六','周日']
export const scenes: Record<Scene, string> = {
  weekday: '星期几', meal: '聚餐', trip: '出差', work: '工作', study: '学习', tag: '自选标签', person: '同行人'
}
export function matchesScene(log: JournalSpan, scene: Scene, term: string) {
  switch (scene) {
    case 'meal': return log.tags.includes('聚餐') || (log.category === 'MEAL' && log.people.length > 0)
    case 'trip': return log.tags.includes('出差')
    case 'work': return log.category === 'WORK'
    case 'study': return log.category === 'STUDY'
    case 'tag': return log.tags.includes(term)
    case 'person': return log.people.includes(term)
    case 'weekday': return true
  }
}
export type ComparisonGroup = {
  key: string; label: string; days: DailyBehavior[]; activeDays: number; total: number
}
export function compareDays(source: DailyBehavior[], logs: JournalSpan[], scene: Scene, term: string, measure: Measure) {
  if ((scene === 'tag' || scene === 'person') && !term) return []
  const label = scene === 'tag' ? '#'+term : scene === 'person' ? term : scenes[scene]
  const labels = scene === 'weekday' ? weekdays : ['有「'+label+'」记录','未记「'+label+'」']
  const groups: ComparisonGroup[] = labels.map((name,i) => ({key:String(i),label:name,days:[],activeDays:0,total:0}))
  const matches = scene === 'weekday' ? [] : logs.filter(log => matchesScene(log,scene,term))
  for (const day of source) {
    if (day.isFuture) continue
    // The API supplies Shanghai calendar dates. Use UTC only for weekday arithmetic on that date.
    const index = scene === 'weekday'
      ? (new Date(day.date+'T00:00:00Z').getUTCDay()+6)%7
      : matches.some(log => log.startDate <= day.date && log.endDate >= day.date) ? 0 : 1
    const group = groups[index]
    group.days.push(day)
    if (measure === 'alcohol' ? day.hasAlcoholRecords : day.hasSmokingRecords) group.activeDays++
    group.total += Number(measure === 'alcohol' ? day.alcoholSessions : day.smokingCount)
  }
  return groups
}
