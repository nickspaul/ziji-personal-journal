<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ArrowUpRight, Banknote, BookOpen, CalendarDays, ChevronDown, ChevronLeft, ChevronRight, CircleHelp, CopyPlus, CreditCard, House, Layers, ListFilter, Pencil, Plus, ReceiptText, Search, ShoppingBag, Tag, Ticket, TrainFront, Trash2, Utensils, Wallet, X } from 'lucide-vue-next'
import { api, deleteRow } from '../api'
import { shanghaiDate } from '../types'
import type {Row} from '../types'
import { adjacentPeriod, anchorInMonth, cashflow, categoryRanking, dayGroups, debtDelta, debtLabels, expenseCents, matchingRows, periodHistory, periodKey, weekColumn } from '../ledgerReview'
import type { DebtKind, Direction, DirectionFilter, LedgerPeriod, LedgerRow } from '../ledgerReview'
import MonthlyBills from './MonthlyBills.vue'
import LedgerDebts from './LedgerDebts.vue'
import LedgerEntry from './LedgerEntry.vue'

const props = defineProps<{ refreshKey?: number; editRequest?: {row?:Row;token:number} }>()
const emit = defineEmits<{ notify: [message: string] }>()
const icons = { 餐饮: Utensils, 交通: TrainFront, 购物: ShoppingBag, 居住: House, 学习: BookOpen, 休闲: Ticket, 工资: Banknote }
function categoryIcon(row: LedgerRow) {
  if(row.debtKind==='REPAYMENT' || row.debtKind==='OPENING')return CreditCard
  if (Object.hasOwn(icons, row.category)) return icons[row.category as keyof typeof icons]
  return row.direction === 'INCOME' ? Wallet : Tag
}
const currency = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', currencyDisplay: 'narrowSymbol' })
const formatCents = (value: number) => currency.format(value / 100)
const weekday = new Intl.DateTimeFormat('zh-CN', { weekday: 'long', timeZone: 'Asia/Shanghai' })
const dayLabel = (date: string) => weekday.format(new Date(date + 'T12:00:00+08:00'))
const period = ref<LedgerPeriod>('month'), anchor = ref(shanghaiDate())
const mobilePane = ref<'flow' | 'review'>('flow')
const periodNames = {
  day:   {unit:'日', title:'当日收支', selected:'所选日期', current:'今天', history:'本周每日支出'},
  month: {unit:'月', title:'月度收支', selected:'所选月份', current:'本月', history:'本月每日支出'},
  year:  {unit:'年', title:'年度收支', selected:'所选年份', current:'今年', history:'全年各月支出'},
}
const periods = Object.keys(periodNames) as LedgerPeriod[]
const caption = computed(() => periodNames[period.value])
const key = computed(() => periodKey(anchor.value, period.value))
const isCurrent = computed(() => key.value === periodKey(shanghaiDate(), period.value))
const previous = computed(() => adjacentPeriod(anchor.value, period.value, -1))
const next = computed(() => adjacentPeriod(anchor.value, period.value, 1))
const dateLabel = computed(() => {
  if (period.value === 'day') return anchor.value + ' ' + dayLabel(anchor.value)
  return anchor.value.slice(0,4) + ' 年 ' + Number(anchor.value.slice(5,7)) + ' 月'
})
const rows = ref<LedgerRow[]>([])
const loading = ref(false), loadError = ref('')
let loadId = 0
async function load() {
  const id = ++loadId
  loading.value = true
  loadError.value = ''
  try {
    const result = await api<LedgerRow[]>('/data/ledger')
    if (id === loadId) rows.value = result
  } catch (error) {
    if (id === loadId) loadError.value = (error as Error).message
  } finally {
    if (id === loadId) loading.value = false
  }
}
watch(() => props.refreshKey, load, { immediate: true })
function selectPeriod(view: LedgerPeriod, date = anchor.value) {
  period.value = view
  anchor.value = date
  scope.value = 'PERIOD'
}
function pickPeriod(event: Event) {
  const input = event.currentTarget as HTMLInputElement
  if (!input.value || !input.validity.valid || (period.value === 'year' && !/^\d{4}$/.test(input.value))) {
    input.value = key.value
    return
  }
  const month = period.value === 'year' ? input.value + anchor.value.slice(4,7) : input.value
  selectPeriod(period.value, period.value === 'day' ? input.value : anchorInMonth(month, anchor.value))
}
function pickBucket(value: string) {
  if (period.value === 'year') selectPeriod('month', anchorInMonth(value, anchor.value))
  else {
    selectPeriod('day', value)
    mobilePane.value = 'flow'
  }
}
const periodRows = computed(() => rows.value.filter(row => periodKey(row.date, period.value) === key.value))
const totals = computed(() => cashflow(periodRows.value))
const hasDebtEvents=computed(()=>periodRows.value.some(row=>row.debtKind))
const history = computed(() => periodHistory(rows.value, anchor.value, period.value))
const maxExpense = computed(() => Math.max(1, ...history.value.map(item => item.expense)))
const rankDirection = ref<Direction>('EXPENSE'), rankExpanded = ref(false)
const ranking = computed(() => categoryRanking(periodRows.value, rankDirection.value))
const visibleRanks = computed(() => rankExpanded.value ? ranking.value : ranking.value.slice(0, 4))
const rankTotal = computed(() => rankDirection.value === 'EXPENSE' ? totals.value.expense : totals.value.income)
const directionName = (direction: Direction) => direction === 'EXPENSE' ? '支出' : '收入'

const query = ref(''), scope = ref('PERIOD'), directionFilter = ref<DirectionFilter>('ALL'), categoryFilter = ref('')
const listTitle = ref<HTMLElement>()
const page = ref(1), pageSize = 15
const sourceRows = computed(() => scope.value === 'PERIOD' ? periodRows.value : rows.value)
const platformFilter = ref('')
const filtered = computed(() => matchingRows(sourceRows.value, query.value, directionFilter.value, categoryFilter.value)
  .filter(row=>!platformFilter.value || row.debtPlatform===platformFilter.value))
const groups = computed(() => dayGroups(filtered.value))
const filterActive = computed(() => !!(query.value.trim() || directionFilter.value !== 'ALL' || categoryFilter.value || platformFilter.value))
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const visibleDays = computed(() => {
  const start = (page.value - 1) * pageSize, end = start + pageSize
  let position = 0
  return groups.value.flatMap(group => {
    const entries = group.entries.slice(Math.max(0, start - position), Math.max(0, end - position))
    position += group.entries.length
    return entries.length ? [{ ...group, entries }] : []
  })
})
watch([key, query, scope, directionFilter, categoryFilter,platformFilter], () => { page.value = 1 })
watch([key, rankDirection], () => { rankExpanded.value = false })
watch(pages, count => { page.value = Math.min(page.value, count) })
function clearFilters() {
  query.value = ''
  directionFilter.value = 'ALL'
  categoryFilter.value = ''
  platformFilter.value = ''
}
function drillDown(category: string) {
  mobilePane.value = 'flow'
  query.value = ''
  scope.value = 'PERIOD'
  directionFilter.value = rankDirection.value
  categoryFilter.value = category
  platformFilter.value = ''
  nextTick(() => listTitle.value?.focus())
}

const busy = ref(false),actionError=ref(''),removing=ref('')
const entry=ref<{date:string;kind:Direction|DebtKind;row?:LedgerRow;repeat?:boolean;platform?:string;key:string}>()
function openEditor(row?:LedgerRow,repeat=false,kind:Direction|DebtKind='EXPENSE',platform?:string,date=isCurrent.value?shanghaiDate():anchor.value) {
  if(busy.value)return
  removing.value=''
  entry.value={row,repeat,kind:row?.debtKind || row?.direction || kind,date,platform,key:crypto.randomUUID()}
}
watch(()=>props.editRequest,request=>{if(request)openEditor(request.row as LedgerRow|undefined)}, {immediate:true})
function openDebt(kind:DebtKind,platform?:string,row?:LedgerRow) {
  const firstDate=kind==='OPENING' && platform
    ? rows.value.filter(r=>r.debtPlatform===platform).map(r=>r.date).sort()[0] : undefined
  openEditor(row,false,kind,platform,firstDate || shanghaiDate())
}
function showPlatform(platform?:string) {
  mobilePane.value = 'flow'
  clearFilters()
  scope.value='ALL'
  if(platform){directionFilter.value='DEBT';platformFilter.value=platform}
  nextTick(()=>listTitle.value?.focus())
}
function afterSave(saved:LedgerRow,wasEdit:boolean) {
  mobilePane.value = 'flow'
  rows.value=[saved,...rows.value.filter(row=>row.id!==saved.id)]
  anchor.value=saved.date
  if(!wasEdit){clearFilters();scope.value='PERIOD';page.value=1}
  entry.value=undefined
  emit('notify',wasEdit?'已更新原账目。':'已记入账本。')
}
async function remove(row: LedgerRow) {
  if (busy.value) return
  busy.value = true
  actionError.value = ''
  try {
    await deleteRow('ledger', row)
    rows.value = rows.value.filter(item => item.id !== row.id)
    if (loading.value) void load()
    removing.value = ''
    emit('notify', '账目已移入回收站，可在数据管理中恢复。')
  } catch (error) { actionError.value = (error as Error).message }
  finally { busy.value = false }
}
</script>

<template>
  <div class="ledger">
    <header class="ledger-tools">
      <div class="period-tools">
        <div class="direction-tabs" role="group" aria-label="账本时间范围">
          <button v-for="view in periods" :key="view" :aria-pressed="period===view" @click="selectPeriod(view)">{{ periodNames[view].unit }}</button>
        </div>
        <div class="month-tools">
          <button class="icon-button" :title="'上一'+caption.unit" :disabled="!previous" @click="previous && selectPeriod(period,previous)"><ChevronLeft :size="17"/></button>
          <label v-if="period==='year'" class="year-picker"><CalendarDays :size="16"/><input type="number" inputmode="numeric" aria-label="选择账本年份" min="1000" max="9999" :value="key" @change="pickPeriod"/> 年</label>
          <label v-else class="month-picker"><CalendarDays :size="16"/><strong>{{ dateLabel }}</strong><ChevronDown :size="13"/>
            <input :type="period==='day'?'date':'month'" :aria-label="period==='day'?'选择账本日期':'选择账本月份'" :min="period==='day'?'1000-01-01':'1000-01'" :max="period==='day'?'9999-12-31':'9999-12'" :value="key" @change="pickPeriod"/>
          </label>
          <button class="icon-button" :title="'下一'+caption.unit" :disabled="!next" @click="next && selectPeriod(period,next)"><ChevronRight :size="17"/></button>
          <button v-if="!isCurrent" class="text-link back-month" @click="selectPeriod(period,shanghaiDate())">{{ caption.current }}</button>
        </div>
      </div>
      <div class="ledger-actions"><LedgerDebts :rows="rows" :ready="!loading && !loadError" @entry="openDebt" @history="showPlatform"/>
        <button class="button primary compact-button" :disabled="!!loadError || loading" @click="openEditor()"><Plus :size="17"/>记一笔</button></div>
    </header>
    <div v-if="loadError" class="error-box" role="alert">{{ loadError }} <button class="text-link" @click="load">重新读取</button></div>
    <div v-if="loading || loadError" class="panel ledger-empty" role="status">{{ loading ? '正在读取账本…' : '账本暂不可用' }}</div>
    <template v-else>
      <section class="panel cash-card" :aria-label="caption.title">
          <header><span class="summary-icon"><Wallet :size="20"/></span><div><h2>{{ caption.title }}</h2><small class="quiet sensitive">{{ periodRows.length }} 笔流水</small></div></header>
          <dl class="cash-numbers sensitive">
            <div class="cash-main"><dt>支出</dt><dd>{{ formatCents(totals.expense) }}</dd></div>
            <div><dt>收入</dt><dd>{{ formatCents(totals.income) }}</dd></div>
            <div><dt title="收入减去消费和息费">收支结余</dt><dd>{{ formatCents(totals.net) }}</dd></div>
          </dl>
          <p v-if="hasDebtEvents" class="cash-out sensitive">现金流出 {{formatCents(totals.cashOut)}} · 其中归还本金 {{formatCents(totals.paidPrincipal)}}</p>
      </section>
      <div class="ledger-view-switch direction-tabs" role="group" aria-label="账本视图">
        <button :aria-pressed="mobilePane==='flow'" @click="mobilePane='flow'">收支明细</button>
        <button :aria-pressed="mobilePane==='review'" @click="mobilePane='review'">统计与账单</button>
      </div>
      <div class="ledger-workspace" :data-view="mobilePane">
      <section class="panel flow-panel" aria-label="收支明细">
        <header><h2 ref="listTitle" tabindex="-1">收支明细</h2><span class="quiet sensitive">{{ filtered.length }} 笔</span></header>
        <div class="filters">
          <label class="ledger-search"><Search :size="15"/><input v-model="query" type="search" aria-label="搜索账目" placeholder="搜索平台、类别、日期、备注或金额"/></label>
          <select v-model="scope" aria-label="搜索日期范围"><option value="PERIOD">{{ caption.selected }}</option><option value="ALL">全部日期</option></select>
          <select v-model="directionFilter" aria-label="筛选收支类型" @change="categoryFilter='';platformFilter=''"><option value="ALL">全部流水</option><option value="EXPENSE">只看支出</option><option value="INCOME">只看收入</option><option value="DEBT">平台负债流水</option></select>
        </div>
        <div v-if="categoryFilter" class="category-filter sensitive"><ListFilter :size="14"/>类别<button @click="categoryFilter=''">{{ categoryFilter }} <X :size="13"/></button></div>
        <div v-if="platformFilter" class="category-filter sensitive"><ListFilter :size="14"/>平台<button @click="platformFilter=''">{{platformFilter}} <X :size="13"/></button></div>
        <p v-if="actionError" class="error-box" role="alert">{{ actionError }}</p>
        <div v-if="visibleDays.length" class="days sensitive">
          <section v-for="day in visibleDays" :key="day.date" class="day">
            <header><h3><time :datetime="day.date">{{ day.date }}</time><small>{{ dayLabel(day.date) }}</small></h3>
              <span><template v-if="filterActive">筛选小计 · </template><template v-if="day.income">收 {{ formatCents(day.income) }}</template>{{ day.income&&day.expense?' · ':'' }}<template v-if="day.expense">支 {{ formatCents(day.expense) }}</template><template v-if="day.paidPrincipal">{{day.income||day.expense?' · ':''}}还本金 {{formatCents(day.paidPrincipal)}}</template></span>
            </header>
            <ul class="lines">
              <li v-for="row in day.entries" :key="row.id">
                <span class="category-icon" :class="{income:row.direction==='INCOME'}"><component :is="categoryIcon(row)" :size="16"/></span>
                <div class="line-description"><strong>{{row.debtKind && row.debtKind!=='CREDIT'?debtLabels[row.debtKind]:row.category}}</strong>
                  <span v-if="row.debtKind">{{row.debtPlatform}} · {{row.debtKind==='CREDIT'?'信用支付':row.debtKind==='OPENING'?'结转本金':'本金 '+formatCents(-debtDelta(row))+' · 息费 '+formatCents(expenseCents(row))}}</span>
                  <span v-if="row.notes" :title="row.notes">{{row.notes}}</span></div>
                <div class="line-amount" :class="{income:row.direction==='INCOME'}"><strong>{{row.debtKind==='OPENING'?'':row.direction==='INCOME'?'+':'−'}}{{currency.format(row.amount)}}</strong><small v-if="row.debtKind==='REPAYMENT'">计支出 {{formatCents(expenseCents(row))}}</small><small v-else-if="row.debtKind==='OPENING'">计支出 ¥0.00</small></div>
                <div v-if="removing===row.id" class="row-controls"><button class="row-confirm" :disabled="busy" @click="remove(row)">确认移除</button><button class="text-link" :disabled="busy" @click="removing=''">取消</button></div>
                <div v-else class="row-controls">
                  <button v-if="row.debtKind!=='OPENING'" class="icon-button" :disabled="busy" :aria-label="'再记一笔 '+row.category" title="按这条再记一笔" @click="openEditor(row,true)"><CopyPlus :size="15"/></button>
                  <button class="icon-button" :disabled="busy" :aria-label="'编辑 '+row.date+' '+row.category" title="编辑账目" @click="openEditor(row)"><Pencil :size="14"/></button>
                  <button class="icon-button" :disabled="busy" :aria-label="'移除 '+row.date+' '+row.category" title="移入回收站" @click="removing=row.id;actionError=''"><Trash2 :size="14"/></button>
                </div>
              </li>
            </ul>
          </section>
        </div>
        <div v-else class="ledger-empty"><ReceiptText :size="23"/><strong>{{ filterActive?'没有符合条件的账目':'还没有收支明细' }}</strong>
          <button class="text-link" @click="filterActive?clearFilters():openEditor()">{{ filterActive?'清除筛选':'记下第一笔' }}</button>
        </div>
        <footer v-if="pages>1"><button class="icon-button" title="上一页账目" :disabled="page===1" @click="page--"><ChevronLeft :size="16"/></button><span>第 {{ page }} / {{ pages }} 页</span><button class="icon-button" title="下一页账目" :disabled="page>=pages" @click="page++"><ChevronRight :size="16"/></button></footer>
        <p class="flow-note"><CircleHelp :size="13"/>支出按消费发生日和还款息费统计。归还本金单列。烟酒用量估价见日常概览。</p>
      </section>
      <aside class="ledger-review" aria-label="支出统计与固定账单">
        <section class="panel history-card">
          <div class="history-heading"><h2><CalendarDays :size="16"/>{{ caption.history }}</h2><span class="quiet">{{ period==='year'?'选月份查看':'选日期查看' }}</span></div>
          <div v-if="period==='month'" class="expense-calendar sensitive">
            <span v-for="w in ['一','二','三','四','五','六','日']" :key="w" class="weekday">周{{ w }}</span>
            <button v-for="(item,i) in history" :key="item.key" :class="{recorded:item.count}" :style="i===0?{gridColumnStart:weekColumn(item.key)}:undefined"
              :aria-label="item.key+'，'+(item.count?'支出 '+formatCents(item.expense):'没有账目')" :title="item.key+' · '+(item.count?formatCents(item.expense):'没有账目')" @click="pickBucket(item.key)">
              <span>{{ Number(item.key.slice(-2)) }}</span><small>{{ item.count?formatCents(item.expense):'—' }}</small>
            </button>
          </div>
          <div v-else class="month-strip sensitive" :class="{'year-strip':period==='year'}">
            <button v-for="item in history" :key="item.key" :aria-current="item.key===anchor?'date':undefined"
              :aria-label="item.key+'，'+(item.count ? '支出 '+formatCents(item.expense) : '没有账目')" :title="item.key+' · '+(item.count ? formatCents(item.expense) : '没有账目')"
              @click="pickBucket(item.key)">
              <b>{{ item.count ? formatCents(item.expense) : '—' }}</b>
              <span class="bar-well" aria-hidden="true"><i :style="{height:(item.expense/maxExpense*100)+'%'}"></i></span>
              <small>{{ period==='year'? Number(item.key.slice(5))+' 月' : item.key.slice(5).replace('-','/') }}</small>
            </button>
          </div>
        </section>
        <section class="panel rank-card">
          <header><h2><Layers :size="17"/>分类排行</h2>
            <div class="direction-tabs" role="group" aria-label="分类排行收支类型"><button v-for="d in (['EXPENSE','INCOME'] as const)" :key="d" :aria-pressed="rankDirection===d" @click="rankDirection=d">{{ directionName(d) }}</button></div>
          </header>
          <ol v-if="ranking.length" class="ranks sensitive">
            <li v-for="[category,total] in visibleRanks" :key="category">
              <button :aria-label="'筛选'+directionName(rankDirection)+'类别：'+category" @click="drillDown(category)">
                <span>{{ category }}</span><strong>{{ formatCents(total) }}</strong>
                <small>{{ (total/rankTotal*100).toFixed(1).replace(/\.0$/,'') }}%</small><ChevronRight :size="13"/>
                <i class="rank-track" aria-hidden="true"><i :style="{width:total/rankTotal*100+'%'}"></i></i>
              </button>
            </li>
          </ol>
          <div v-else class="rank-empty"><ReceiptText :size="22"/><p>{{ caption.selected }}没有{{ directionName(rankDirection) }}记录</p><button class="text-link" @click="openEditor(undefined,false,rankDirection)">记一笔{{ directionName(rankDirection) }} <ArrowUpRight :size="13"/></button></div>
          <button v-if="ranking.length>4" class="text-link rank-more" @click="rankExpanded=!rankExpanded">{{ rankExpanded?'收起':'查看全部 '+ranking.length+' 类' }}</button>
        </section>
      <MonthlyBills :rows="rows" :refresh-key="refreshKey" @changed="load" @notify="emit('notify',$event)"/>
      </aside>
      </div>
    </template>

    <LedgerEntry v-if="entry" :key="entry.key" v-bind="{date:entry.date,row:entry.row,repeat:entry.repeat,kind:entry.kind,platform:entry.platform}"
      :rows="rows" :ready="!loadError && !loading" @close="entry=undefined" @saved="afterSave"/>
  </div>
</template>

<style scoped src="./ledger.css"></style>
