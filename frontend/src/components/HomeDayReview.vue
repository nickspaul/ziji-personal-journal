<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { CalendarDays, ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { adjacentPeriod, anchorInMonth, weekColumn } from '../ledgerReview'
import { shiftDay } from '../journal'
import { money, number, shanghaiDate } from '../types'

type Day = {
  date: string
  hasSmokingRecords: boolean
  hasAlcoholRecords: boolean
  hasIntimacyRecords: boolean
  smokingCount: number
  alcoholSessions: number
  alcoholMl: number | null
  alcoholUnknownVolumeCount: number
  ethanolEquivalent42Ml: number | null
  alcoholUnknownEthanolCount: number
  intimacyCount: number
  masturbationCount: number
  sexCount: number
  consumptionCost: number | null
  unknownCostCount: number
}
const props = defineProps<{
  from: string
  to: string
  selectedDate: string
  daily: Day[]
  loading: boolean
  error?: string
  scope: '7' | '30' | 'all'
  earliest: string | null
}>()
const emit = defineEmits<{ select: [date: string]; month: [date: string] }>()
const listMode = ref<'day' | 'recent'>('day')
watch(() => props.selectedDate, () => { listMode.value = 'day' })
const weekdays = ['一', '二', '三', '四', '五', '六', '日']
const monthKey = computed(() => props.selectedDate.slice(0, 7))
const monthName = computed(() => monthKey.value.slice(0, 4) + ' 年 ' + Number(monthKey.value.slice(5)) + ' 月')
const calendarTitle = computed(() => {
  if (props.scope !== '7' || props.from.slice(0, 7) === props.to.slice(0, 7)) return monthName.value
  return props.from.replaceAll('-', '.') + ' — ' + props.to.replaceAll('-', '.')
})
const minDate = computed(() => props.scope === 'all' ? props.earliest || props.from : props.from)
const maxDate = computed(() => {
  const rangeEnd = props.to
  return props.scope === 'all' ? shanghaiDate() : rangeEnd
})
const byDate = computed(() => new Map(props.daily.map(day => [day.date, day])))
const selected = computed(() => byDate.value.get(props.selectedDate))

function hasRecords(day: Day | undefined) {
  return Boolean(day && (day.hasSmokingRecords || day.hasAlcoholRecords || day.hasIntimacyRecords))
}
const calendar = computed(() => {
  if (props.scope === '7') {
    return Array.from({length: 7}, (_, index) => shiftDay(props.from, index))
  }
  if (!monthKey.value) return []
  const lastDay = Number(anchorInMonth(monthKey.value, monthKey.value + '-31').slice(8))
  return Array.from({length: lastDay}, (_, index) => monthKey.value + '-' + String(index + 1).padStart(2, '0'))
})
const weekdayLabels = computed(() => props.scope === '7' ? calendar.value.map(date => weekdays[weekColumn(date) - 1]) : weekdays)
function dayNumber(date: string) {
  const day = Number(date.slice(8))
  return props.scope === '7' && day === 1 ? Number(date.slice(5, 7)) + '/1' : String(day)
}
function neighboringMonth(step: 1 | -1) {
  if (!props.selectedDate) return null
  const next = adjacentPeriod(props.selectedDate, 'month', step)
  if (!next || next.slice(0, 7) < minDate.value.slice(0, 7) || next.slice(0, 7) > maxDate.value.slice(0, 7)) return null
  if (next < minDate.value) return minDate.value
  if (next > maxDate.value) return maxDate.value
  return next
}
function choose(date: string | null) {
  if (!date || date < minDate.value || date > maxDate.value) return
  listMode.value = 'day'
  if (date === props.selectedDate) return
  if (props.scope === 'all' && date.slice(0, 7) !== monthKey.value) emit('month', date)
  else emit('select', date)
}
function chooseInput(event: Event) {
  const input = event.currentTarget as HTMLInputElement
  if (input.validity.valid && input.value.length === 10) choose(input.value)
  else input.value = props.selectedDate
}
function cellLabel(date: string) {
  const day = byDate.value.get(date)
  if (date < minDate.value || date > maxDate.value) return date + ' · 超出查看范围'
  if (props.loading || props.error || !day) return date
  return date + (hasRecords(day) ? ' · 有记录' : ' · 无记录')
}
</script>

<template>
  <section class="day-review panel" aria-labelledby="day-review-heading">
    <header class="day-heading">
      <h3 id="day-review-heading"><CalendarDays :size="16"/>按日查看</h3>
      <label>跳到日期<input type="date" aria-label="跳到日期" :value="selectedDate" :min="minDate" :max="maxDate" required @change="chooseInput"></label>
    </header>
    <div class="day-layout" :class="{weekly: scope === '7'}">
      <div class="calendar-pane">
        <div class="month-nav">
          <strong>{{calendarTitle}}</strong>
          <div v-if="scope !== '7'">
            <button type="button" aria-label="上个月" :disabled="!neighboringMonth(-1)" @click="choose(neighboringMonth(-1))"><ChevronLeft :size="16"/></button>
            <button type="button" aria-label="下个月" :disabled="!neighboringMonth(1)" @click="choose(neighboringMonth(1))"><ChevronRight :size="16"/></button>
          </div>
        </div>
        <div class="month-grid" role="group" :aria-label="calendarTitle">
          <template v-if="scope !== '7'"><span v-for="day in weekdays" :key="day" class="weekday" aria-hidden="true">{{day}}</span></template>
          <button v-for="(date, index) in calendar" :key="date" type="button" class="calendar-day"
            :style="scope !== '7' && index === 0 ? {gridColumnStart: weekColumn(date)} : undefined"
            :aria-label="cellLabel(date)" :title="cellLabel(date)" :aria-pressed="date === selectedDate"
            :disabled="date < minDate || date > maxDate" @click="choose(date)">
            <span v-if="scope === '7'" class="weekday">周{{weekdayLabels[index]}}</span>{{dayNumber(date)}}<i v-if="!loading && !error && hasRecords(byDate.get(date))" class="record-dot sensitive" aria-hidden="true"></i>
          </button>
        </div>
        <p class="calendar-legend"><i></i> 有行为记录</p>
      </div>
      <div class="detail-pane" :aria-busy="loading">
        <div class="detail-controls">
          <div class="segmented" role="group" aria-label="查看明细">
            <button type="button" :aria-pressed="listMode==='day'" :class="{active:listMode==='day'}" @click="listMode='day'">所选日</button>
            <button type="button" :aria-pressed="listMode==='recent'" :class="{active:listMode==='recent'}" @click="listMode='recent'">近期 5 条</button>
          </div>
          <slot name="all"></slot>
        </div>
        <template v-if="listMode==='recent'">
          <p v-if="loading" class="detail-message" role="status">正在读取近期记录…</p>
          <p v-else-if="error" class="detail-message" role="alert">{{error}}</p>
          <slot v-else name="recent"></slot>
        </template>
        <template v-else>
        <h4><time :datetime="selectedDate">{{Number(selectedDate.slice(5, 7))}} 月 {{Number(selectedDate.slice(8))}} 日</time><span>周{{weekdays[weekColumn(selectedDate) - 1]}}</span></h4>
        <p v-if="loading" class="detail-message" role="status">正在读取当天记录…</p>
        <p v-else-if="error" class="detail-message" role="alert">{{error}}</p>
        <p v-else-if="!selected" class="detail-message">当天统计暂不可用</p>
        <template v-else>
          <dl class="day-metrics sensitive">
            <div><dt>吸烟</dt><dd>{{number(selected.smokingCount)}} <small>根</small></dd></div>
            <div><dt>饮酒</dt><dd>{{selected.alcoholMl == null ? '待补用量' : number(selected.alcoholMl)}} <small v-if="selected.alcoholMl != null">ml</small></dd>
              <span>{{number(selected.alcoholSessions)}} 次</span>
            </div>
            <div><dt>私人行为</dt><dd>{{number(selected.intimacyCount)}} <small>次</small></dd>
              <span v-if="selected.hasIntimacyRecords">自慰 {{number(selected.masturbationCount)}} · 性生活 {{number(selected.sexCount)}}</span>
            </div>
            <div><dt>烟酒估算费用</dt><dd>{{money(selected.consumptionCost)}}</dd></div>
          </dl>
          <p v-if="selected.hasAlcoholRecords" class="day-note sensitive">
            {{selected.ethanolEquivalent42Ml == null ? '折合 42° 白酒用量待补' : '折合 42° 白酒 ' + number(selected.ethanolEquivalent42Ml) + ' ml'}}
            <span v-if="selected.alcoholMl != null && selected.alcoholUnknownVolumeCount > 0"> · 部分饮用量待补</span>
            <span v-if="selected.ethanolEquivalent42Ml != null && selected.alcoholUnknownEthanolCount > 0"> · 仅折算资料齐全的记录</span>
          </p>
          <p v-if="selected.unknownCostCount > 0" class="day-note sensitive">{{number(selected.unknownCostCount)}} 条费用待补{{selected.consumptionCost != null ? '，金额为已知小计' : ''}}</p>
          <slot v-if="hasRecords(selected)"></slot>
          <p v-else class="detail-message">这一天没有行为记录</p>
        </template>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.detail-controls { display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:6px; margin-bottom:8px; }
.detail-controls .segmented button { font-size:12px; padding:5px 8px; }
.day-review { min-width: 0; }
.day-heading, .month-nav, .month-nav > div, .day-heading h3, .day-heading label, .detail-pane h4, .calendar-legend { display: flex; align-items: center; }
.day-heading { justify-content: space-between; flex-wrap: wrap; gap: 8px; padding: 12px 18px; border-bottom: 1px solid var(--border); }
.day-heading h3 { gap: 7px; margin: 0; font-size: 15px; font-weight: 600; }
.day-heading h3 svg { color: var(--muted); }
.day-heading label { gap: 7px; color: var(--muted); font-size: 11px; }
.day-heading input { width: auto; max-width: 135px; padding: 4px 6px; border: 1px solid var(--border); border-radius: 6px; background: var(--panel-soft); color: var(--text); font: inherit; font-variant-numeric: tabular-nums; }
.day-layout { display: grid; grid-template-columns: minmax(210px, 0.8fr) minmax(0, 1.8fr); }
.calendar-pane, .detail-pane { min-width: 0; padding: 11px 18px 13px; }
.calendar-pane { border-right: 1px solid var(--border); background: color-mix(in srgb,var(--panel-soft) 55%,var(--panel)); }
.month-nav { justify-content: space-between; gap: 8px; margin-bottom: 6px; }
.month-nav strong, .detail-pane h4 { font-size: 13px; font-weight: 550; }
.month-nav > div { gap: 3px; }
.month-nav button { display: grid; place-items: center; width: 27px; height: 26px; border-radius: 5px; color: var(--muted); }
.month-nav button:hover:enabled { color: var(--text); background: var(--panel-soft); }
.month-nav button:disabled, .calendar-day:disabled { opacity: .32; cursor: default; }
.month-grid { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 2px; }
.weekday { text-align: center; color: var(--muted); font-size: 11px; padding-bottom: 3px; }
.calendar-day { position: relative; min-width: 0; min-height: 27px; padding: 2px 1px 6px; border: 1px solid transparent; border-radius: 5px; color: var(--text); font-size: 12px; font-variant-numeric: tabular-nums; line-height: 1.3; }
.calendar-day:hover:enabled { background: var(--panel-soft); border-color: var(--border); }
.calendar-day[aria-pressed=true], .calendar-day[aria-pressed=true]:hover { color: var(--on-action); background: var(--action); border-color: var(--action); }
.calendar-day[aria-pressed=true] :is(.weekday,.record-dot) { color: inherit; }
.calendar-day[aria-pressed=true] .record-dot { background: currentColor; }
.weekly { grid-template-columns: minmax(0,1fr); }
.weekly .calendar-pane { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 12px; border-right: 0; border-bottom: 1px solid var(--border); padding: 8px 16px; }
.weekly .month-nav, .weekly .calendar-legend { margin: 0; }
.weekly .month-grid { flex: 1 1 340px; gap: 4px; }
.weekly .calendar-day { display: inline-flex; align-items: baseline; justify-content: center; gap: 4px; min-height: 34px; padding: 6px 2px; font-size: 14px; font-weight: 600; }
.weekly .weekday { font-size: 11px; font-weight: 450; padding: 0; }
.weekly .calendar-legend { font-size: 11px; }
.weekly .detail-pane { padding-top: 12px; }
@media(max-width:730px) {
  .weekly .calendar-pane { display: grid; grid-template-columns: 1fr auto; gap: 6px; padding: 8px 12px; }
  .weekly .month-grid { grid-column: 1/-1; grid-row: 2; }
  .weekly .calendar-day { flex-direction: column; align-items: center; gap: 1px; }
  .weekly .calendar-legend { justify-self: end; }
}
.calendar-legend { gap: 6px; margin: 8px 0 0; font-size: 10px; color: var(--muted); }
.record-dot, .calendar-legend i { width: 4px; height: 4px; border-radius: 50%; background: var(--mint); }
.record-dot { position: absolute; bottom: 3px; left: calc(50% - 2px); }
.detail-pane h4 { gap: 7px; margin: 3px 0 10px; }
.detail-pane h4 span { font-size: 11px; font-weight: 400; color: var(--muted); }
.day-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 9px; margin: 0 0 7px; }
.day-metrics dt, .day-metrics span, .day-note { color: var(--muted); font-size: 11px; }
.day-metrics dd { margin: 3px 0 0; font-size: 16px; font-weight: 550; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.day-metrics small { font-weight: 400; font-size: 11px; color: var(--muted); }
.day-note { margin: 3px 0; }
.detail-message { border-top: 1px solid var(--border); margin: 9px 0 0; padding: 11px 0 0; font-size: 12px; color: var(--muted); }
.day-review :is(button,input):focus-visible { outline: 2px solid var(--text); outline-offset: 1px; }
@media (max-width: 760px) {
  .day-layout { grid-template-columns: 1fr; }
  .calendar-pane { border: 0; border-bottom: 1px solid var(--border); }
}
@media (max-width: 440px) {
  .day-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
