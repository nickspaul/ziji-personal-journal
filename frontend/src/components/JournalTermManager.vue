<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ArrowLeft, ChevronLeft, ChevronRight, Pencil, Plus, Search, Tag, Trash2, Users, X } from 'lucide-vue-next'
import { api } from '../api'
import type { JournalFacets } from '../journal'

type Kind = 'tag' | 'person'
type Affected = { collection: 'journal' | 'tasks'; id: string; title: string; date: string | null; deleted: boolean; version: number }
type Preview = { previewToken: string; count: number; rows: Affected[]; pinnedChanged?: boolean }
const props = defineProps<{ facets: JournalFacets; initialKind?: Kind }>()
const emit = defineEmits<{
  close: []
  changed: [change: {kind: Kind; from?: string; to?: string; allChanged?: boolean}]
  search: [term: {kind: Kind; name: string}]
  recap: [name: string]
}>()
const dialog = ref<HTMLDialogElement>(), targetInput = ref<HTMLInputElement>()
const kind = ref<Kind>(props.initialKind ?? 'person'), mode = ref<'list'|'rename'|'delete'>('list')
const label = computed(() => kind.value === 'person' ? '同行人' : '标签')
const query = ref(''), source = ref(''), target = ref('')
const preview = ref<Preview>(), answers = ref(new Map<string,boolean>()), page = ref(1)
const busy = ref(false), error = ref(''), notice = ref('')
const abort = new AbortController()
const pageSize = 12
const inventory = computed(() => kind.value === 'tag'
  ? props.facets.tags.map(item => item.name)
  : props.facets.people)
function normalized(value: string) {
  return kind.value === 'tag' ? value.trim().replace(/^#+/, '').trim().toLowerCase() : value.trim()
}
const newName = computed(() => normalized(query.value))
const matching = computed(() => inventory.value.filter(name => name.toLowerCase().includes(newName.value.toLowerCase())))
const replacement = computed(() => normalized(target.value))
const title = computed(() => mode.value === 'list' ? '同行人与标签' : (mode.value === 'rename' ? '改名或合并' : '删除') + label.value)
const history = computed(() => [...(preview.value?.rows ?? [])].sort((a,b) => (b.date || '').localeCompare(a.date || '') || (a.title || '').localeCompare(b.title || '', 'zh-CN')))
const pages = computed(() => Math.max(1, Math.ceil(history.value.length / pageSize)))
const visibleRows = computed(() => history.value.slice((page.value-1)*pageSize, page.value*pageSize))
const changeCount = computed(() => [...answers.value.values()].filter(Boolean).length)
const complete = computed(() => !!preview.value && answers.value.size === preview.value.rows.length)
const key = (row: Affected) => row.collection + ':' + row.id
const display = (name: string) => kind.value === 'tag' ? '#' + name : name
const post = <T,>(action: string, body: object) => api<T>('/journal/terms/' + action, {
  method: 'POST', body: JSON.stringify(body), signal: abort.signal
})
function clearReview() {
  preview.value = undefined
  answers.value = new Map()
  page.value = 1
}
function toList() {
  mode.value = 'list'
  error.value = ''
  clearReview()
}
function switchKind(value: Kind) {
  kind.value = value
  query.value = ''
  notice.value = ''
  error.value = ''
}
async function addName() {
  const name = newName.value
  if (busy.value || !name || inventory.value.includes(name)) return
  busy.value = true
  error.value = notice.value = ''
  try {
    await post('add', {kind: kind.value, name})
    query.value = ''
    notice.value = '已添加' + label.value + '“' + name + '”。'
    emit('changed', {kind: kind.value, to: name})
  } catch (e) { if (!abort.signal.aborted) error.value = (e as Error).message }
  finally { busy.value = false }
}
function edit(action: 'rename'|'delete', name: string) {
  mode.value = action
  source.value = name
  target.value = ''
  error.value = notice.value = ''
  clearReview()
  if (action === 'delete') void loadPreview()
  else nextTick(() => targetInput.value?.focus())
}
function actionData() {
  return {kind: kind.value, from: source.value, ...(mode.value === 'rename' ? {to: replacement.value} : {})}
}
async function loadPreview() {
  if (busy.value || (mode.value === 'rename' && (!replacement.value || replacement.value === source.value))) return
  clearReview()
  error.value = ''
  busy.value = true
  try { preview.value = await post<Preview>('preview', actionData()) }
  catch (e) { if (!abort.signal.aborted) error.value = (e as Error).message }
  finally { busy.value = false }
}
async function apply() {
  const plan = preview.value
  if (busy.value || !plan || !complete.value) return
  const decisions = plan.rows.map(row => ({collection: row.collection, id: row.id, change: answers.value.get(key(row))!}))
  const details = {...actionData(), allChanged: decisions.every(d => d.change)}
  busy.value = true
  error.value = ''
  try {
    const result = await post<{updated: number}>('apply', {...actionData(), previewToken: plan.previewToken, decisions})
    notice.value = (mode.value === 'delete' ? '已从可选列表删除' : '已更新可选列表中的') + '“' + source.value + '”。已修改 ' + result.updated + ' 条记录。'
    query.value = ''
    toList()
    emit('changed', details)
  } catch (e) {
    clearReview()
    if (!abort.signal.aborted) error.value = (e as Error).message + ' 请重新预览并核对记录。'
  } finally { busy.value = false }
}
function close() { if (!busy.value) emit('close') }
onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => { abort.abort(); dialog.value?.close() })
</script>

<template>
  <dialog ref="dialog" class="term-manager" aria-label="管理同行人与标签" @cancel.prevent="close">
    <header>
      <button v-if="mode!=='list'" type="button" class="icon-button" aria-label="返回名单" :disabled="busy" @click="toList"><ArrowLeft :size="17"/></button>
      <h2>{{title}}</h2>
      <button type="button" class="icon-button" aria-label="关闭管理窗口" :disabled="busy" @click="close"><X :size="17"/></button>
    </header>
    <div v-if="mode==='list'" class="body">
      <div class="segmented" role="group" aria-label="管理类别">
        <button type="button" :aria-pressed="kind==='person'" :class="{active:kind==='person'}" :disabled="busy" @click="switchKind('person')"><Users :size="14"/>同行人</button>
        <button type="button" :aria-pressed="kind==='tag'" :class="{active:kind==='tag'}" :disabled="busy" @click="switchKind('tag')"><Tag :size="14"/>标签</button>
      </div>
      <form class="add-row" @submit.prevent="addName">
        <input v-model="query" type="search" maxlength="80" :aria-label="'查找或新增'+label" :placeholder="'查找或新增'+label" :disabled="busy" @input="error=notice=''"/>
        <button type="submit" class="button secondary" :disabled="busy || !newName || inventory.includes(newName)"><Plus :size="14"/>新增</button>
      </form>
      <ul v-if="matching.length" class="inventory" :aria-label="label+'名单'">
        <li v-for="name in matching" :key="name">
          <button type="button" class="lookup" :disabled="busy" :aria-label="'查找'+display(name)+'的记录'" :title="'查找相关记录'" @click="emit('search',{kind,name})"><span class="sensitive">{{display(name)}}</span><Search :size="12"/></button>
          <button v-if="kind==='person'" type="button" class="text-link" :disabled="busy" :aria-label="'回看与'+name+'的共同记录'" @click="emit('recap',name)">回顾</button>
          <button type="button" class="icon-button" :aria-label="'改名'+label+' '+name" title="改名或合并" :disabled="busy" @click="edit('rename',name)"><Pencil :size="14"/></button>
          <button type="button" class="icon-button" :aria-label="'删除'+label+' '+name" title="删除" :disabled="busy" @click="edit('delete',name)"><Trash2 :size="14"/></button>
        </li>
      </ul>
      <p v-else class="quiet">{{newName?'没有匹配项，可点“新增”保存这个名称。':'还没有'+label+'，可在上方输入名称并新增。'}}</p>
      <p v-if="notice" class="notice sensitive" role="status">{{notice}}</p>
    </div>
    <div v-else class="body">
      <p class="source"><span>{{mode==='rename'?'原名称':'即将从可选列表删除'}}</span><strong class="sensitive">{{display(source)}}</strong></p>
      <form v-if="mode==='rename' && !preview" class="add-row" @submit.prevent="loadPreview">
        <input ref="targetInput" v-model="target" aria-label="新名称" placeholder="新名称，或输入已有名称合并" maxlength="80" required :disabled="busy"/>
        <button class="button secondary" :disabled="busy || !replacement || replacement===source">预览影响</button>
      </form>
      <p v-if="busy && !preview" class="quiet" role="status">正在读取受影响的记录…</p>
      <template v-if="preview">
        <p v-if="mode==='rename'" class="source"><span>{{inventory.includes(replacement)?'合并到':'新名称'}}</span><strong class="sensitive">{{display(replacement)}}</strong>
          <button class="text-link" type="button" :disabled="busy" @click="clearReview(); nextTick(() => targetInput?.focus())">重选</button>
        </p>
        <p v-if="history.length">有 {{history.length}} 条记录使用这个{{label}}。请逐条选择{{mode==='rename'?'保留原名或改用新名称。':'保留原名或从该条记录移除。'}}</p>
        <p v-else class="quiet">目前没有记录使用这个{{label}}。</p>
        <ol v-if="history.length" class="affected">
          <li v-for="row in visibleRows" :key="key(row)">
            <div class="row-info"><small>{{row.collection==='journal'?'生活日志':'待办'}} · {{row.date || '未设日期'}}<em v-if="row.deleted">回收站</em></small><strong class="sensitive">{{row.title || '未命名记录'}}</strong></div>
            <div role="group" :aria-label="'如何处理'+row.title" class="choices">
              <label :class="{chosen:answers.get(key(row))===false}"><input type="radio" :name="key(row)" :checked="answers.get(key(row))===false" :disabled="busy" @change="answers.set(key(row),false)"/>保留原名</label>
              <label :class="{chosen:answers.get(key(row))===true}"><input type="radio" :name="key(row)" :checked="answers.get(key(row))===true" :disabled="busy" @change="answers.set(key(row),true)"/>{{mode==='rename'?'使用新名':'从记录移除'}}</label>
            </div>
          </li>
        </ol>
        <nav v-if="pages>1" aria-label="受影响记录翻页"><button class="icon-button" aria-label="上一页记录" :disabled="page<=1" @click="page--"><ChevronLeft :size="16"/></button>{{page}} / {{pages}}<button class="icon-button" aria-label="下一页记录" :disabled="page>=pages" @click="page++"><ChevronRight :size="16"/></button></nav>
        <p v-if="mode==='rename' && preview.pinnedChanged" class="quiet">若全部记录都改用新名，使用原名的固定筛选也会随之更新。</p>
      </template>
      <button v-else-if="mode==='delete' && error" class="button secondary" :disabled="busy" type="button" @click="loadPreview">重新预览</button>
    </div>
    <p v-if="error" class="error-box" role="alert">{{error}}</p>
    <footer v-if="preview">
      <span class="quiet" aria-live="polite">{{history.length ? '已选择 '+answers.size+'/'+history.length+' 条，将修改 '+changeCount+' 条' : '仅更新可选列表'}}</span>
      <button class="button secondary" type="button" :disabled="busy" @click="toList">取消</button>
      <button class="button" :class="mode==='delete'?'danger':'primary'" type="button" :disabled="busy || !complete" @click="apply">{{busy?'正在保存…':mode==='delete'?'确认删除':inventory.includes(replacement)?'确认合并':'确认改名'}}</button>
    </footer>
  </dialog>
</template>

<style scoped>
.term-manager { width:min(580px,calc(100vw - 24px)); max-height:calc(100dvh - 28px); padding:0; overflow:auto; border:1px solid var(--border); border-radius:12px; background:var(--panel); color:var(--text); box-shadow:var(--shadow); }
.term-manager::backdrop { background:#080a10ae; }
.term-manager header,.term-manager footer,.term-manager nav,.inventory li,.affected li,.add-row,.source,.choices { display:flex; align-items:center; gap:8px; }
.term-manager header,.term-manager footer { position:sticky; z-index:1; padding:10px 16px; background:var(--panel); }
.term-manager header { top:0; border-bottom:1px solid var(--border); }
.term-manager header h2 { flex:1; margin:0; font-size:16px; }
.term-manager .body { display:grid; gap:12px; padding:14px 16px; }
.term-manager .segmented { width:fit-content; }
.term-manager .segmented button { display:inline-flex; align-items:center; gap:5px; }
.add-row input { min-width:0; flex:1; }
.term-manager .button { min-height:32px; padding:6px 10px; white-space:nowrap; }
.inventory,.affected { list-style:none; margin:0; padding:0; }
.inventory { max-height:48dvh; overflow:auto; }
.inventory li,.affected li { padding:5px 2px; border-bottom:1px solid var(--border); }
.inventory li:last-child,.affected li:last-child { border-bottom:0; }
.inventory .lookup { display:flex; align-items:center; gap:7px; min-width:0; flex:1; padding:6px 0; text-align:left; color:var(--text); }
.lookup span,.row-info strong,.source strong { overflow-wrap:anywhere; }
.lookup svg { flex:none; color:var(--muted); }
.inventory .icon-button { width:30px; height:30px; }
.term-manager p { margin:0; font-size:13px; line-height:1.5; }
.quiet,.source>span,.row-info small { color:var(--muted); font-size:12px; }
.source { align-items:baseline; flex-wrap:wrap; }
.source strong,.row-info strong { font-size:13px; font-weight:550; }
.affected li { justify-content:space-between; flex-wrap:wrap; padding:8px 0; }
.row-info { flex:1 1 160px; min-width:0; display:grid; gap:3px; }
.row-info em { font-style:normal; margin-left:6px; color:var(--pink); }
.choices { gap:4px; }
.choices label { cursor:pointer; display:flex; align-items:center; gap:4px; padding:5px 7px; color:var(--muted); border:1px solid var(--border); border-radius:5px; font-size:12px; }
.choices input { margin:0; width:auto; accent-color:var(--mint); }
.choices .chosen { border-color:var(--mint); background:var(--mint-bg); color:var(--mint); }
.term-manager nav { justify-content:center; font-size:12px; }
.term-manager footer { bottom:0; flex-wrap:wrap; justify-content:flex-end; border-top:1px solid var(--border); }
.term-manager footer span { margin-right:auto; }
.term-manager .error-box { margin:0 16px 12px; }
.notice { color:var(--mint); }
.term-manager :is(button,input):focus-visible { outline:2px solid var(--mint); outline-offset:1px; }
</style>
