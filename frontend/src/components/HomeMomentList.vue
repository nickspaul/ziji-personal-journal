<script lang="ts">
import type { Row } from '../types'
export type HomeMoment = { row: Row; kind: 'smoking' | 'drinks' | 'intimacy' | 'sessions'; at: string }
</script>

<script setup lang="ts">
import { Cigarette, Heart, Wine } from 'lucide-vue-next'
import { state } from '../api'
import { recordCostLabel, recordName } from '../productPricing'
import { number, privateActivityLabel, shortDate, shortTime } from '../types'

defineProps<{ entries: HomeMoment[]; showDate?: boolean }>()
const emit = defineEmits<{ open: [entry: HomeMoment] }>()

function itemLabel({ row, kind }: HomeMoment) {
  if (kind === 'sessions') return row.context || '饮酒场次'
  if (kind === 'intimacy') return `${privateActivityLabel(row.activityType)} · ${row.count || 1} 次`
  const name = recordName(row.snapshotName)
  if (kind === 'smoking') return `${name} · ${row.count} 根`
  return `${name} · ${row.actualMl == null ? '待补用量' : number(row.actualMl) + ' ml'}`
}
function timeLabel({ row, at }: HomeMoment) {
  if (row.timePrecision === 'DATE') return '仅记日期'
  if (row.timePrecision === 'APPROX') return `约 ${at.slice(11, 13)} 点`
  return shortTime(at)
}
const linkLabels: Record<string, string> = {
  DURING_DRINKING: '饮酒期间', POSSIBLE_AFTER_DRINKING: '估算酒后时段',
  RECENT_RECORDED_DRINKING: '近期有饮酒记录', INSUFFICIENT_DATA: '酒后状态不确定',
  NO_RECORDED_RECENT_DRINKING: '近期无饮酒记录'
}
function linkLabel({ row, kind }: HomeMoment) {
  if (kind !== 'smoking' && kind !== 'intimacy') return ''
  const links = kind === 'smoking' ? state.analytics.smokingLinks : state.analytics.links
  const link = links?.[row.id]
  if (!link) return ''
  if (link.source === 'MANUAL') return '本人确认' + (link.effective === 'YES' ? '酒后' : link.effective === 'NO' ? '非酒后' : '不确定')
  return linkLabels[link.status] || '饮酒关联待判断'
}
</script>
<template>
  <div class="timeline">
    <button v-for="entry in entries" :key="entry.kind + entry.row.id" class="timeline-row" @click="emit('open', entry)">
      <span class="timeline-symbol" :class="entry.kind === 'smoking' || entry.kind === 'intimacy' ? entry.kind : 'alcohol'">
        <Cigarette v-if="entry.kind === 'smoking'" :size="15"/><Heart v-else-if="entry.kind === 'intimacy'" :size="15"/><Wine v-else :size="15"/>
      </span>
      <span class="timeline-content sensitive">
        <strong>{{ itemLabel(entry) }}</strong>
        <span>{{ [showDate && shortDate(entry.at), timeLabel(entry), linkLabel(entry)].filter(Boolean).join(' · ') }}</span>
      </span>
      <span v-if="entry.kind !== 'intimacy'" class="timeline-cost sensitive">{{ entry.kind === 'sessions' ? '查看场次' : recordCostLabel(entry.row, entry.kind) }}</span>
    </button>
  </div>
</template>
<style scoped>
.timeline { padding: 0; }
.timeline-row { padding: 8px 0; border-color: var(--border); }
.timeline-row:hover strong { text-decoration: underline; text-underline-offset: 3px; }
.timeline-content { flex: 1; }
.timeline-content strong { max-width: 100%; color: var(--text); font-size: 13px; }
.timeline-content span { color: var(--muted); font-size: 12px; margin-top: 3px; }
.timeline-row .timeline-symbol { width: 29px; height: 29px; border-radius: 7px; }
.timeline-row .timeline-cost { color: var(--muted); font-size: 12px; font-variant-numeric: tabular-nums; }
</style>
