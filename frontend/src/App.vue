<script setup lang="ts">
import {computed,onMounted,onBeforeUnmount,ref,watch,nextTick} from 'vue'
import {LayoutDashboard,BarChart3,Wallet,Database,Settings as SettingsIcon,ShieldCheck,Eye,EyeOff,Plus,RefreshCw,Menu,X,Activity,LockKeyhole,LoaderCircle,Check,Info,CalendarDays,ArrowRight,BookOpen,Download,ChevronDown} from 'lucide-vue-next'
import Dashboard from './components/Dashboard.vue'
import Ledger from './components/Ledger.vue'
import ReportExport from './components/ReportExport.vue'
import LifeJournal from './components/LifeJournal.vue'
import JournalEditor from './components/JournalEditor.vue'
import TaskEditor from './components/TaskEditor.vue'
import BodyDashboard from './components/BodyDashboard.vue'
import BodyEditor from './components/BodyEditor.vue'
import DataManager from './components/DataManager.vue'
import Settings from './components/Settings.vue'
import Analytics from './components/Analytics.vue'
import RecordDrawer from './components/RecordDrawer.vue'
import GenericEditor from './components/GenericEditor.vue'
import {state,authStatus,authenticate,api,refresh,refreshAnalytics,refreshLifetimeSummary,saveRow,deleteRow,restoreRow,saveSettings} from './api'
import type {Row,PrivateActivityType} from './types'
import {dataGroups} from './dataManagement'
import {clearDrafts} from './drafts'
import {localNow,shanghaiDate,shortDate,privateActivityLabels} from './types'
import {shiftDay} from './journal'
import {anchorInMonth} from './ledgerReview'
const navGroups = [
  {label:'日常', items:[
    {id:'dashboard', label:'日常概览', icon:LayoutDashboard},
    {id:'journal', label:'日志与待办', icon:BookOpen},
    {id:'ledger', label:'日常记账', icon:Wallet},
  ]},
  {label:'回顾', items:[
    {id:'analytics', label:'行为分析', icon:BarChart3},
    {id:'body', label:'身体数据', icon:Activity},
  ]},
  {label:'管理', items:[
    {id:'manager', label:'数据管理', icon:Database},
    {id:'settings', label:'个人设置', icon:SettingsIcon},
  ]},
]
const nav = navGroups.flatMap(group => group.items)
const managerKinds = dataGroups.flatMap(group => group.collections)
function readRoute(hash:string) {
  const [path, query=''] = hash.replace(/^#/, '').split('?')
  const [section, detail=''] = path.split('/')
  if (section === 'history' || section === 'products') return {page:'manager', detail:section === 'history' ? 'smoking' : 'products', product:''}
  const page = nav.some(item => item.id === section) ? section : 'dashboard'
  const subpage = page === 'manager' ? (managerKinds.includes(detail) ? detail : 'smoking') :
    page === 'analytics' && ['association','estimate','cost','alcohol'].includes(detail) ? detail : ''
  return {page, detail:subpage, product:page === 'manager' && subpage === 'prices' ? new URLSearchParams(query).get('product') || '' : ''}
}
const initialRoute = readRoute(location.hash)
const managerCollection = ref(initialRoute.page === 'manager' ? initialRoute.detail : 'smoking')
const managerProduct = ref(initialRoute.product)
const page=ref(initialRoute.page),days=ref(30),to=ref(shanghaiDate()),sidebar=ref(false),password=ref(''),passwordAgain=ref(''),showPassword=ref(false),authBusy=ref(false),authError=ref(''),connectionError=ref(''),record=ref<{kind:'smoking'|'alcohol'|'intimacy';row?:Row;session?:Row}|null>(null),editor=ref<{collection:string;row?:Row;preset?:Record<string,any>}|null>(null),toast=ref<{message:string;undo?:()=>Promise<void>}|null>(null)
const journalRefresh=ref(0)
const ledgerEdit=ref<{row?:Row;token:number}>()
let ledgerEditToken=0
const exportMode = ref<'quick'|'custom'|null>(null)
const narrowScreen = window.matchMedia('(max-width: 800px)')
const isNarrow = ref(narrowScreen.matches)
const navPanel = ref<HTMLElement>()
const navTrigger = ref<HTMLButtonElement>()
function syncViewport() {
  isNarrow.value = narrowScreen.matches
  if (!isNarrow.value) sidebar.value = false
}
function navKeys(event: KeyboardEvent) {
  if (!sidebar.value || !navPanel.value) return
  if (event.key === 'Escape') { sidebar.value = false; return }
  if (event.key !== 'Tab') return
  const items = [...navPanel.value.querySelectorAll<HTMLElement>('a[href],button:not(:disabled)')].filter(el => el.offsetWidth > 0)
  const first = items[0], last = items[items.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
watch(sidebar, async open => {
  await nextTick()
  if (open) navPanel.value?.querySelector<HTMLElement>('[aria-current="page"]')?.focus()
  else if (isNarrow.value) navTrigger.value?.focus({preventScroll:true})
})
onMounted(() => { narrowScreen.addEventListener('change', syncViewport); window.addEventListener('keydown', navKeys) })
onBeforeUnmount(() => { narrowScreen.removeEventListener('change', syncViewport); window.removeEventListener('keydown', navKeys) })
watch(() => state.authenticated, authenticated => { if (!authenticated) { exportMode.value = null; sidebar.value = false } })
const bodyDelete=ref<Row|null>(null),bodyDeleteBusy=ref(false),bodyDeleteError=ref('')
async function removeBody(){if(!bodyDelete.value)return;const row=bodyDelete.value;bodyDeleteBusy.value=true;bodyDeleteError.value='';try{await deleteRow('body',row);bodyDelete.value=null;await reload();notify('身体测量已移入回收站。',async()=>{await restoreRow('body',{...row,version:row.version+1});await reload();notify('身体测量已恢复。')})}catch(e){bodyDeleteError.value=(e as Error).message}finally{bodyDeleteBusy.value=false}}
function repairAlcohol(detail:Record<string,any>){if(detail.collection==='body'){go('body');openEditor('body',state.collections.body?.find(r=>r.id===detail.id));return}if(detail.collection==='sessions'){const session=state.collections.sessions?.find(r=>r.id===(detail.sessionId||detail.id));if(session){record.value={kind:'alcohol',session};return}}if(detail.collection==='drinks'){const row=state.collections.drinks?.find(r=>r.id===detail.id);if(row){openEditor('drinks',row);return}}go('manager/'+(detail.collection==='sessions'?'sessions':'drinks'))}
type HomeScope = '7' | '30' | 'all'
const homeScope = ref<HomeScope>('7')
const selectedDay = ref(to.value)
const detailMonth = ref('')
// Daily statistics for a lifetime view are fetched one calendar month at a time.
const reviewMonth = computed(() => {
  if (page.value === 'dashboard' && homeScope.value === 'all') return selectedDay.value.slice(0, 7)
  if (page.value === 'analytics' && days.value === 0) return detailMonth.value
  return ''
})
const activeDays = computed(() => page.value === 'dashboard' ? Number(homeScope.value) : days.value || 30)
const from = computed(() => reviewMonth.value ? reviewMonth.value + '-01' : shiftDay(to.value, 1 - activeDays.value))
const analysisEnd = computed(() => {
  if (!reviewMonth.value) return to.value
  const monthEnd = anchorInMonth(reviewMonth.value, reviewMonth.value + '-31')
  return monthEnd < to.value ? monthEnd : to.value
})
const homeLoading = computed(() => state.analytics.from !== from.value || state.analytics.to !== analysisEnd.value ||
  (!state.analytics.daily && !state.analytics.warnings))
function changeHomeScope(scope: HomeScope) {
  homeScope.value = scope
  if (scope !== 'all' && selectedDay.value < shiftDay(to.value, 1 - Number(scope))) selectedDay.value = to.value
}
function selectHomeDay(date: string) {
  const earliest = homeScope.value === 'all' ? state.lifetimeSummary.from : from.value
  if (date <= to.value && (!earliest || date >= earliest)) selectedDay.value = date
}
const currentNav=computed(()=>nav.find(n=>n.id===page.value)||nav[0]),todayLabel=computed(()=>new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',month:'long',day:'numeric',weekday:'long'}).format(new Date()))
let toastTimer:ReturnType<typeof setTimeout>|undefined,lockTimer:ReturnType<typeof setInterval>|undefined,lastActivity=Date.now()
function notify(message:string,undo?:()=>Promise<void>){if(toastTimer)clearTimeout(toastTimer);toast.value={message,undo};toastTimer=setTimeout(()=>toast.value=null,undo?10000:6000)}
function applyRoute(id:string, scroll:boolean) {
  const route = readRoute(id)
  if (route.page !== 'ledger') ledgerEdit.value=undefined
  if (page.value === 'dashboard' && route.page === 'analytics') {
    detailMonth.value = reviewMonth.value
    days.value = homeScope.value === 'all' ? 0 : Number(homeScope.value)
  }
  page.value = route.page
  if (route.page === 'manager') {
    managerCollection.value = route.detail
    managerProduct.value = route.product
  }
  sidebar.value = false
  if (scroll) void nextTick(() => {
    const behavior = state.settings.reducedMotion ? 'instant' : 'smooth'
    const target = route.page === 'analytics' && route.detail ? document.getElementById(route.detail) : null
    if (target) target.scrollIntoView({behavior, block:'start'})
    else window.scrollTo({top:0, behavior})
  })
}
function go(id:string) {
  if (id === 'manager') id = 'manager/' + managerCollection.value + (managerProduct.value ? '?product=' + encodeURIComponent(managerProduct.value) : '')
  if (location.hash !== '#' + id) window.history.pushState(null,'','#'+id)
  applyRoute(id,true)
}
function openCollection(name:string, product='') {
  go('manager/' + name + (product ? '?product=' + encodeURIComponent(product) : ''))
}
function openRecord(kind:'smoking'|'alcohol'|'intimacy'){record.value={kind}}
function openEditor(collection:string,row?:Row,preset?:Record<string,any>){if(collection==='ledger'){ledgerEdit.value={row,token:++ledgerEditToken};go('ledger');return}if(!row&&['smoking','drinks','intimacy'].includes(collection)){openRecord(collection==='smoking'?'smoking':collection==='intimacy'?'intimacy':'alcohol');return}editor.value={collection,row,preset}}
async function updateAnalytics(includeSummary = true){
  if (!state.authenticated || !['dashboard','analytics','manager'].includes(page.value)) return
  const needsSummary = includeSummary && page.value === 'dashboard' && homeScope.value === 'all'
  await Promise.all([refreshAnalytics(from.value,analysisEnd.value), ...(needsSummary ? [refreshLifetimeSummary()] : [])])
}
async function reload(){try{await refresh();journalRefresh.value++;await updateAnalytics()}catch(e){notify((e as Error).message)}}
async function init(){state.checking=true;connectionError.value='';try{await authStatus();if(state.authenticated){await refresh();await updateAnalytics()}}catch(e){connectionError.value=(e as Error).message}finally{state.checking=false;if(state.authenticated && readRoute(location.hash).detail)applyRoute(location.hash,true)}}
async function login(){authError.value='';if(!state.configured&&password.value!==passwordAgain.value){authError.value='两次输入的口令不一致，请再检查一下。';return}authBusy.value=true;try{await authenticate(password.value);password.value='';passwordAgain.value='';await updateAnalytics();if(readRoute(location.hash).detail)applyRoute(location.hash,true);lastActivity=Date.now()}catch(e){authError.value=(e as Error).message}finally{authBusy.value=false}}
async function lock(){try{await api('/auth/logout',{method:'POST'});state.authenticated=false;state.collections={};state.analytics={};state.lifetimeSummary={};state.lifetimeError='';ledgerEdit.value=undefined;record.value=null;editor.value=null;bodyDelete.value=null;await nextTick();clearDrafts();await authStatus()}catch(e){notify((e as Error).message)}}
function reminder(kind:string){if(!['smoking','sessions','drinks','intimacy'].includes(kind))return '';if(state.settings.reminderMode==='OFF')return '';const time=localNow().slice(11,16),start=state.settings.quietStart||'23:00',end=state.settings.quietEnd||'08:00';if(start<end?time>=start&&time<end:time>=start||time<end)return '';const key=`ziji-reminders-${shanghaiDate()}`;const count=Number(localStorage.getItem(key)||0),limit=Number(state.settings.dailyReminderLimit??3);if(count>=limit)return '';localStorage.setItem(key,String(count+1));return kind==='smoking'?'如果愿意，下次想抽时可以先等一会儿。':kind==='intimacy'?'也给舒适和休息留一点空间。':kind==='sessions'||kind==='drinks'?'可以考虑到这里结束，给今晚留一些休息时间。':''}
const quickSaving = ref(false)
async function quick(activityType: PrivateActivityType) {
  if (quickSaving.value) return
  quickSaving.value = true
  try {
    const row = await saveRow('intimacy', { activityType, occurredAt:localNow(), timePrecision:'EXACT', count:1, manualAlcohol:'AUTO' })
    await reload()
    notify(`已记录${privateActivityLabels[activityType]} 1 次。${reminder('intimacy')}`, async () => {
      await deleteRow('intimacy',row)
      await reload()
      notify('记录已撤销，可在回收站恢复。')
    })
  } catch (error) { notify((error as Error).message) }
  finally { quickSaving.value = false }
}
async function afterSaved(row:Row,collection:string){const wasNew=!record.value?.row&&!editor.value?.row;record.value=null;editor.value=null;await reload();notify(`记录已保存。${reminder(collection)}`,wasNew&&['smoking','intimacy'].includes(collection)?async()=>{await deleteRow(collection,row);await reload();notify('记录已撤销。')}:undefined)}
async function togglePrivacy(){try{await saveSettings({hidden:!state.settings.hidden})}catch(e){notify((e as Error).message)}}
function editFromDashboard(row:Row,collection:string){if(collection==='smoking'||collection==='intimacy')record.value={kind:collection,row};else openEditor(collection,row)}
function activity(){lastActivity=Date.now()}
function hashChange(){applyRoute(location.hash,true)}
watch(to, (today, previous) => {
  if (selectedDay.value === previous || (homeScope.value !== 'all' && selectedDay.value < shiftDay(today, 1 - Number(homeScope.value)))) selectedDay.value = today
})
watch([homeScope, () => state.lifetimeSummary.from], ([scope, earliest]) => {
  if (scope === 'all' && earliest && selectedDay.value < earliest) selectedDay.value = earliest
})
watch([page,from,analysisEnd,homeScope,to], (current, previous) => {
  const [view,,,scope,today] = current
  const enteringLifetime = view !== previous[0] || scope !== previous[3] || today !== previous[4]
  void updateAnalytics(enteringLifetime || !state.lifetimeSummary.totals)
})
watch(()=>state.settings.theme,v=>document.documentElement.dataset.theme=v||'dark',{immediate:true})
watch(()=>state.settings.reducedMotion,v=>document.documentElement.dataset.motion=v?'reduced':'normal',{immediate:true})
onMounted(()=>{init();window.addEventListener('pointerdown',activity);window.addEventListener('keydown',activity);window.addEventListener('hashchange',hashChange);lockTimer=setInterval(()=>{if(state.authenticated&&Date.now()-lastActivity>15*60*1000)lock();else if(state.authenticated&&['dashboard','analytics'].includes(page.value)&&document.visibilityState==='visible'){if(to.value!==shanghaiDate())to.value=shanghaiDate();else updateAnalytics()}},60000)})
onBeforeUnmount(()=>{window.removeEventListener('pointerdown',activity);window.removeEventListener('keydown',activity);window.removeEventListener('hashchange',hashChange);if(lockTimer)clearInterval(lockTimer);if(toastTimer)clearTimeout(toastTimer)})
</script>
<template>
<div v-if="state.checking" class="loading-screen" role="status">
  <span class="journal-mark" aria-hidden="true"></span><LoaderCircle class="spin" :size="19"/>正在打开本机空间…
</div>
<main v-else-if="!state.authenticated || connectionError" class="auth-page">
  <div class="auth-book">
    <div class="book-cover"><span class="journal-mark" aria-hidden="true"></span><strong>自记</strong><time :datetime="to">{{todayLabel}}</time></div>
    <section class="unlock-panel" :aria-label="state.configured?'解锁自记':'设置本地口令'">
      <h1>{{state.configured?'解锁你的手记':'设置本地口令'}}</h1>
      <p>{{state.configured?'输入口令，打开这台电脑上的记录。':'用至少 8 位字符设置口令，保护这台电脑上的记录。'}}</p>
      <div v-if="connectionError" class="connection-state">
        <p role="alert"><Info :size="16"/> {{connectionError}}</p>
        <button class="button secondary" @click="init"><RefreshCw :size="16"/>重新连接本机服务</button>
      </div>
      <form v-else @submit.prevent="login">
        <label class="field"><span>本地口令</span>
          <span class="unlock-password"><input v-model="password" :type="showPassword?'text':'password'" :minlength="state.configured?1:8" required :autocomplete="state.configured?'current-password':'new-password'" autofocus/><button type="button" class="icon-button" :aria-label="showPassword?'隐藏口令':'显示口令'" :title="showPassword?'隐藏口令':'显示口令'" @click="showPassword=!showPassword"><EyeOff v-if="showPassword" :size="16"/><Eye v-else :size="16"/></button></span>
        </label>
        <label v-if="!state.configured" class="field"><span>再次输入口令</span><input v-model="passwordAgain" :type="showPassword?'text':'password'" minlength="8" required autocomplete="new-password"/></label>
        <p v-if="authError" class="error-box" role="alert">{{authError}}</p>
        <button class="button primary full-width unlock-submit" :disabled="authBusy"><LoaderCircle v-if="authBusy" class="spin" :size="16"/>{{authBusy?'正在打开':state.configured?'解锁':'开始记录'}}<ArrowRight :size="16"/></button>
      </form>
      <p class="unlock-note"><ShieldCheck :size="15"/>本机存储 · 15 分钟无操作后自动锁定</p>
    </section>
  </div>
</main>
<div v-else class="app-shell" :class="{privacy:state.settings.hidden}">
  <div v-if="sidebar" class="nav-scrim" @click="sidebar=false"></div>
  <aside id="workspace-nav" ref="navPanel" class="workspace-nav" :class="{open:sidebar}" :inert="isNarrow && !sidebar" :role="sidebar?'dialog':undefined" :aria-modal="sidebar || undefined" aria-label="工作区导航">
    <div class="nav-brand-row">
      <a class="workspace-brand" href="#dashboard" aria-label="自记 · 日常概览" @click.prevent="go('dashboard')"><span class="journal-mark" aria-hidden="true"></span>自记</a>
      <button class="icon-button nav-dismiss" title="关闭导航" @click="sidebar=false"><X :size="18"/></button>
    </div>
    <div class="nav-lists">
      <nav v-for="group in navGroups" :key="group.label" class="shell-nav" :aria-label="group.label">
        <div class="nav-group-title">{{group.label}}</div>
        <a v-for="n in group.items" :key="n.id" :href="'#'+n.id" :aria-current="page===n.id?'page':undefined" @click.exact.prevent="go(n.id)">
          <component :is="n.icon" :size="17" :stroke-width="1.7"/><span>{{n.label}}</span>
        </a>
      </nav>
    </div>
    <div class="workspace-status">
      <span><ShieldCheck :size="16"/>本机存储</span>
      <button class="icon-button" title="刷新数据" @click="reload" :disabled="state.loading"><RefreshCw :size="15" :class="{spin:state.loading}"/></button>
      <button class="icon-button" title="锁定空间" @click="sidebar=false;lock()"><LockKeyhole :size="15"/></button>
    </div>
  </aside>
  <main class="workspace-main" :inert="sidebar"><div class="workspace-content">
    <header class="shell-header">
      <div class="shell-title">
        <button ref="navTrigger" class="icon-button nav-trigger" aria-label="打开导航" aria-controls="workspace-nav" :aria-expanded="sidebar" @click="sidebar=true"><Menu :size="20"/></button>
        <h1>{{currentNav.label}}</h1><span v-if="page==='dashboard'" class="shell-date">{{todayLabel}}</span>
      </div>
      <div class="shell-actions">
        <label v-if="page==='analytics'" class="date-control"><CalendarDays :size="15"/><select v-model.number="days" aria-label="统计时间范围"><option v-if="detailMonth" :value="0">{{detailMonth}} 月度</option><option :value="7">最近 7 天</option><option :value="30">最近 30 天</option><option :value="90">最近 90 天</option></select></label>
        <button v-if="page==='body'" class="button primary compact-button" @click="openEditor('body')"><Plus :size="15"/>记录测量</button>
        <div class="report-split" role="group" aria-label="导出分析记录">
          <button type="button" title="下载最近 30 天的 Markdown，默认不含私人记录" aria-label="导出近 30 天" @click="exportMode='quick'"><Download :size="15"/><span>导出近 30 天</span></button>
          <button type="button" title="选择导出日期、内容和格式" aria-label="选择导出日期、内容和格式" aria-haspopup="dialog" @click="exportMode='custom'"><ChevronDown :size="14"/></button>
        </div>
        <button class="icon-button privacy-switch" :aria-pressed="!!state.settings.hidden" :title="state.settings.hidden?'显示记录':'隐私遮挡'" @click="togglePrivacy"><EyeOff v-if="state.settings.hidden" :size="18"/><Eye v-else :size="18"/></button>
      </div>
    </header>
<div v-if="state.error" class="error-box main-error" role="alert">{{state.error}}<button class="text-link" @click="reload">重试</button></div>
<Dashboard v-if="page==='dashboard'" :from="from" :to="analysisEnd" :days="activeDays" :scope="homeScope" :selected-date="selectedDay" :loading="homeLoading" :quick-saving="quickSaving" @scope="changeHomeScope" @select-day="selectHomeDay" @month="selectHomeDay" @record="openRecord" @quick="quick" @view="go" @edit="editFromDashboard" @repair="repairAlcohol"/>
<Ledger v-else-if="page==='ledger'" :refresh-key="journalRefresh" :edit-request="ledgerEdit" @notify="notify"/>
<LifeJournal v-else-if="page==='journal'" :refresh-key="journalRefresh"/>
<Analytics v-else-if="page==='analytics'" :from="from" :to="analysisEnd" @view="go" @repair="repairAlcohol"/>
<BodyDashboard v-else-if="page==='body'" @edit="row=>openEditor('body',row)" @remove="row=>{bodyDeleteError='';bodyDelete=row}"/>
<DataManager v-else-if="page==='manager'" manager :collection="managerCollection" :product-filter="managerProduct" @choose="openCollection" :refresh-key="journalRefresh" @edit="openEditor" @changed="updateAnalytics" @notify="notify"/>
<Settings v-else-if="page==='settings'" @changed="updateAnalytics" @notify="notify"/>
</div></main>
<ReportExport v-if="exportMode" :quick="exportMode==='quick'" @close="exportMode=null" @notify="notify"/>
<RecordDrawer v-if="record" :key="record.kind+(record.row?.id||record.session?.id||'new')" :kind="record.kind" :row="record.row" :session="record.session" @close="record=null" @saved="afterSaved" @refresh="reload"/>
<BodyEditor v-if="editor?.collection==='body'" :key="editor.row?.id||'new-body'" :row="editor.row" @close="editor=null" @saved="afterSaved"/>
<JournalEditor v-else-if="editor?.collection==='journal'" :row="editor.row" :preset="editor.preset" @close="editor=null" @saved="afterSaved" @refresh="reload"/>
<TaskEditor v-else-if="editor?.collection==='tasks'" :row="editor.row" :preset="editor.preset" @close="editor=null" @saved="afterSaved"/>
<GenericEditor v-else-if="editor" :key="editor.collection+editor.row?.id" :collection="editor.collection" :row="editor.row" :preset="editor.preset" @close="editor=null" @saved="afterSaved"/>
<div v-if="bodyDelete" class="overlay centered" @click.self="bodyDelete=null"><section class="modal"><header><h2>移除这次身体测量？</h2><button class="icon-button" title="关闭" @click="bodyDelete=null"><X :size="20"/></button></header><p>测量于 {{shortDate(bodyDelete.measuredAt)}}。趋势和酒精估算会随之更新，之后可在回收站恢复。</p><p v-if="bodyDeleteError" class="error-box">{{bodyDeleteError}}</p><footer><button class="button secondary" @click="bodyDelete=null">保留</button><button class="button danger" :disabled="bodyDeleteBusy" @click="removeBody">移入回收站</button></footer></section></div>
</div><Transition name="toast"><div v-if="toast" class="toast" role="status"><span class="toast-icon"><Check :size="17"/></span><p>{{toast.message}}</p><button v-if="toast.undo" class="toast-undo" @click="toast.undo().catch(e=>notify(e.message))">撤销</button><button class="icon-button" title="关闭提示" @click="toast=null"><X :size="16"/></button></div></Transition>
</template>

<style src="./shell.css"></style>
