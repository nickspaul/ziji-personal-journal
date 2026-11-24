<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { CalendarDays, ChevronDown, Cigarette, Wine } from 'lucide-vue-next'
import { api, state } from '../api'
import { compareDays, matchesScene, scenes, type JournalSpan, type Measure, type Scene } from '../dayComparison'
import { recordName } from '../productPricing'
import { localNow, number, shortDate, shortTime, type Row } from '../types'

const props = defineProps<{from:string;to:string}>()
const measure = ref<Measure>('alcohol'), scene = ref<Scene>('weekday'), term = ref('')
const logs = ref<JournalSpan[]>([]), busy = ref(false), error = ref(''), retry = ref(0)
const selectedGroup = ref(''), selectedDate = ref(''), shownDates = ref(14)
const behavior = computed(() => measure.value === 'alcohol' ? '饮酒' : '吸烟')
const analytics = computed(() => state.analytics.from === props.from && state.analytics.to === props.to ? state.analytics : undefined)
const daily = computed(() => analytics.value?.daily)
const needsTerm = computed(() => scene.value === 'tag' || scene.value === 'person')
const needContext = computed(() => !state.settings.hidden && (scene.value!=='weekday' || !!selectedDate.value))
const terms = computed(() => {
  const key = scene.value === 'tag' ? 'tags' : 'people'
  return [...new Set(logs.value.flatMap(log => log[key]))].sort((a,b) => a.localeCompare(b,'zh-CN'))
})

watch([() => props.from, () => props.to, daily, needContext, retry], async ([from,to],_,onCleanup) => {
  const controller = new AbortController()
  onCleanup(() => controller.abort())
  error.value = ''
  logs.value = []
  if (!daily.value || !needContext.value) { busy.value=false; return }
  busy.value = true
  try {
    const rows = await api<JournalSpan[]>('/analytics/day-context?' + new URLSearchParams({from,to}), {signal:controller.signal})
    if (!controller.signal.aborted) {
      logs.value = rows
      if (needsTerm.value && !terms.value.includes(term.value)) term.value = ''
    }
  } catch(e) {
    if (!controller.signal.aborted) error.value=(e as Error).message
  } finally {
    if (!controller.signal.aborted) busy.value=false
  }
}, {immediate:true})
watch(scene, () => { term.value='' })
watch([measure,scene,term,() => props.from,() => props.to,() => state.settings.hidden], () => {
  selectedGroup.value=''; selectedDate.value=''; shownDates.value=14
})

const groups = computed(() => compareDays(daily.value || [], logs.value, scene.value, term.value, measure.value))
const selected = computed(() => groups.value.find(group => group.key === selectedGroup.value))
const newestFirst = computed(() => selected.value ? [...selected.value.days].reverse() : [])
const dayLogs = computed(() => logs.value.filter(log => log.startDate <= selectedDate.value && log.endDate >= selectedDate.value
  && matchesScene(log,scene.value,term.value)))
const sceneDefinition: Record<Scene,string> = {
  weekday:'按上海日期分组。', meal:'聚餐取带「聚餐」标签的日志，或填写了同行人的餐饮日志。',
  trip:'出差取带「出差」标签的日志。', work:'工作取工作分类的日志。', study:'学习取学习分类的日志。',
  tag:'按日志里的标签精确匹配。', person:'按日志里的同行人姓名精确匹配。'
}
const rangeHint = computed(() => {
  const sampled=groups.value.filter(group => group.days.length)
  if (!sampled.length || !sampled.some(group => group.activeDays)) return ''
  if (sampled.length < 2) return '只有一组有日期可统计。'
  const best=sampled.reduce((a,b) => a.activeDays*b.days.length>=b.activeDays*a.days.length?a:b)
  const tied=sampled.filter(group => group.activeDays*best.days.length===best.activeDays*group.days.length)
  if (tied.length===sampled.length) return '各组有记录的天数占比相同。'
  return '本期占比最高：'+tied.map(group=>group.label).join('、')+'。'
})
function openGroup(key:string) {
  selectedGroup.value=selectedGroup.value===key?'':key
  selectedDate.value=''
  shownDates.value=14
}
const dayRows = computed(() => {
  const collections = measure.value==='smoking' ? ['smoking'] : ['sessions','drinks']
  const now = localNow()
  return collections.flatMap(kind => (state.collections[kind] || []).flatMap(row => {
    const at = String(kind==='sessions' ? row.startedAt || '' : row.occurredAt || '')
    if (row.deleted || !at || at.slice(0,10)!==selectedDate.value || at.slice(0,19)>now) return []
    return [{kind,row,at}]
  })).sort((a,b)=>a.at.localeCompare(b.at)||a.kind.localeCompare(b.kind))
})
function recordLabel(kind:string,row:Row) {
  if (kind==='sessions') return '饮酒场次 · '+(row.context || '未填场景')
  if (kind==='smoking') return recordName(row.snapshotName,'吸烟记录')+' · '+number(row.count,0)+' 根'
  return recordName(row.snapshotName,'饮酒明细')+' · '+(row.actualMl==null?'待补用量':number(row.actualMl)+' ml')
}
function timeLabel(at:string,precision:string) {
  if (precision==='DATE') return '仅记日期'
  if (precision==='APPROX') return '约 '+at.slice(11,13)+' 点'
  return shortTime(at)
}
</script>

<template>
  <section class="panel comparison-panel">
    <template v-if="state.settings.hidden"><h3>日期对照</h3><p class="small-note">隐私遮挡已开启。</p></template>
    <template v-else>
    <header>
      <div><h3>哪些日子更常{{behavior}}？</h3><p>{{ shortDate(from) }} 至 {{ shortDate(to) }} · 占比按每组全部日期计算</p></div>
      <div class="segmented" role="group" aria-label="比较行为">
        <button type="button" :class="{active:measure==='alcohol'}" :aria-pressed="measure==='alcohol'" @click="measure='alcohol'"><Wine :size="14"/>饮酒</button>
        <button type="button" :class="{active:measure==='smoking'}" :aria-pressed="measure==='smoking'" @click="measure='smoking'"><Cigarette :size="14"/>吸烟</button>
      </div>
    </header>
    <div class="controls">
      <label>分组<select v-model="scene"><option v-for="(label,key) in scenes" :key="key" :value="key">{{label}}</option></select></label>
      <label v-if="needsTerm">{{scene==='tag'?'标签':'姓名'}}<select v-model="term" class="sensitive"><option value="">请选择</option><option v-for="item in terms" :key="item" :value="item">{{item}}</option></select></label>
    </div>
    <p v-if="!daily && analytics?.warnings?.length" role="alert" class="small-note">{{analytics.warnings[0]}}</p>
    <p v-else-if="!daily || (busy && scene!=='weekday')" class="small-note" role="status">正在读取日期记录…</p>
    <p v-else-if="error && scene!=='weekday'" role="alert" class="small-note">{{error}} <button class="text-link" @click="retry++">重试</button></p>
    <p v-else-if="needsTerm && !term" class="small-note">{{terms.length?'选一个名称，查看有无这项记录的日期。':'这段时间的日志还没有记过'+(scene==='tag'?'标签。':'同行人。')}}</p>
    <template v-else>
      <div class="table-scroll">
        <table><thead><tr><th>日期组</th><th>{{behavior}}天 / 总天</th><th>占比</th><th>{{measure==='alcohol'?'场次':'总根数'}}</th><th v-if="measure==='smoking'">日均根数</th></tr></thead>
          <tbody class="sensitive"><tr v-for="group in groups" :key="group.key" :class="{selected:group.key===selectedGroup}">
            <th scope="row"><button :disabled="!group.days.length" :aria-expanded="selectedGroup===group.key" :aria-label="'查看'+group.label+'的日期'" @click="openGroup(group.key)">{{group.label}} <ChevronDown :size="13"/></button></th>
            <td>{{group.activeDays}} / {{group.days.length}}</td><td>
              <span class="rate"><i v-if="group.days.length" aria-hidden="true"><b :class="measure" :style="{width:group.activeDays/group.days.length*100+'%'}"/></i>{{group.days.length ? number(group.activeDays/group.days.length*100,1)+'%' : '—'}}</span>
            </td>
            <td>{{group.total}}</td><td v-if="measure==='smoking'">{{group.days.length ? number(group.total/group.days.length,1) : '—'}}</td>
          </tr></tbody>
        </table>
      </div>
      <p v-if="rangeHint" class="result-hint sensitive">{{rangeHint}}</p>
      <div v-if="selected" class="detail">
        <strong class="sensitive">{{selected.label}} · {{selected.days.length}} 天</strong>
        <div class="dates sensitive" role="group" aria-label="查看日期明细">
          <button v-for="day in newestFirst.slice(0,shownDates)" :key="day.date" :aria-pressed="selectedDate===day.date"
            :class="{chosen:selectedDate===day.date,recorded:measure==='alcohol'?day.hasAlcoholRecords:day.hasSmokingRecords}" @click="selectedDate=day.date">
            {{day.date.slice(5)}} · {{measure==='alcohol'?(day.hasAlcoholRecords?'有饮酒':'0'):(day.smokingCount+' 根')}}
          </button>
          <button v-if="shownDates<newestFirst.length" @click="shownDates+=14">更多日期</button>
        </div>
        <div v-if="selectedDate" class="sources sensitive">
          <h4><CalendarDays :size="14"/>{{selectedDate}} · 记录明细</h4>
          <ul v-if="dayRows.length"><li v-for="{kind,row,at} in dayRows" :key="kind+row.id"><span>{{timeLabel(at,row.timePrecision)}}</span> {{recordLabel(kind,row)}}<small v-if="row.notes">{{row.notes}}</small></li></ul>
          <p v-else>当天没有{{behavior}}记录。</p>
          <p v-if="busy" role="status">正在读取生活日志…</p>
          <p v-else-if="error" role="alert">{{error}} <button class="text-link" @click="retry++">重试</button></p>
          <template v-else><h4>{{scene==='weekday'?'当天生活日志':'匹配场景的日志'}}</h4>
            <ul v-if="dayLogs.length"><li v-for="log in dayLogs" :key="log.id">{{log.title}}<small>{{[log.startDate!==log.endDate?log.startDate+' 至 '+log.endDate:'',log.people.join('、'),log.tags.map(tag=>'#'+tag).join(' ')].filter(Boolean).join(' · ')}}</small></li></ul>
            <p v-else>{{scene==='weekday'?'当天没有生活日志。':'当天没有匹配该场景的日志。'}}</p>
          </template>
        </div>
      </div>
      <p class="small-note">{{sceneDefinition[scene]}}{{scene==='weekday'?'':' 跨日日志覆盖起止日期，每天只计一次。'}} 未记{{behavior}}的天数按 0 计。{{measure==='alcohol'?'有饮酒场次或饮用明细的日期都算饮酒日，场次只计在开始日。':'日均根数按该组全部日期计算。'}}</p>
    </template>
    </template>
  </section>
</template>

<style scoped>
.comparison-panel {padding:18px 22px;min-width:0}
.comparison-panel header,.controls,.controls label,.comparison-panel .segmented button,.dates,.sources h4 {display:flex;align-items:center;gap:8px}
.comparison-panel header {justify-content:space-between;flex-wrap:wrap}
.comparison-panel h3 {margin:0;font-size:17px}
.comparison-panel header p,.small-note,.detail p {margin:5px 0;color:var(--muted);font-size:12px;line-height:1.65}
.controls {margin:14px 0 8px;flex-wrap:wrap}
.controls label {font-size:12px;color:var(--muted)}
.controls select {font:inherit;color:var(--text);background:var(--input);border:1px solid var(--border);border-radius:6px;padding:5px 9px;max-width:220px}
table {width:100%;border-collapse:collapse;font-size:12px;font-variant-numeric:tabular-nums;white-space:nowrap}
th,td {padding:6px 9px;text-align:right;border-bottom:1px solid var(--border);font-weight:400}
th:first-child {text-align:left;padding-left:0;white-space:normal;overflow-wrap:anywhere}
thead th {color:var(--muted);font-size:11px}
.rate {display:inline-flex;align-items:center;gap:8px}
.rate i {height:4px;width:36px;border-radius:4px;background:var(--border);overflow:hidden}
.rate b {display:block;height:100%;background:var(--mint)}
.rate b.alcohol {background:#9aabff}
tbody button {display:inline-flex;align-items:center;gap:5px;padding:3px 0;border:0;background:transparent;color:var(--text);text-align:left;cursor:pointer;font:inherit}
tbody button:disabled {opacity:.55;cursor:default}
.selected button,.result-hint {color:var(--mint)}
.result-hint {font-size:12px;margin:10px 0}
.detail {padding:12px;margin:12px 0;background:var(--input);border-radius:8px;font-size:12px}
.detail>strong {font-weight:550}
.dates {flex-wrap:wrap;gap:5px;margin:8px 0}
.dates button {border:1px solid var(--border);border-radius:5px;background:var(--panel);color:var(--muted);padding:5px 7px;font-size:11px;cursor:pointer}
.dates .recorded {color:var(--text)}
.dates .chosen {color:var(--mint);border-color:var(--mint)}
.sources {border-top:1px solid var(--border);margin-top:9px}
.sources h4 {font-size:12px;margin:10px 0 5px;font-weight:550}
.sources ul {padding-left:18px;margin:6px 0;line-height:1.7}
.sources small {display:block;color:var(--muted);overflow-wrap:anywhere}
.comparison-panel button:focus-visible,.comparison-panel select:focus-visible {outline:2px solid var(--mint);outline-offset:2px}
@media(max-width:600px){.comparison-panel {padding:14px}.comparison-panel h3{font-size:15px}th,td{padding:6px 4px}}
</style>
