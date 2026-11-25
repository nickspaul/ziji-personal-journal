<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue'
import {Check,CheckSquare2,LoaderCircle,X} from 'lucide-vue-next'
import {api,saveRow,state} from '../api'
import {recordDrafts} from '../drafts'
import {shanghaiDate} from '../types'
import type {Row} from '../types'
import {cleanTask,taskStatuses} from '../journal'
import TagInput from './TagInput.vue'
const props=defineProps<{row?:Row;preset?:Record<string,any>}>()
const emit=defineEmits<{close:[];saved:[row:Row,collection:string]}>()
const draftScope=props.row ? '' : props.preset?.title || ''
const draftKey=`task:${props.row?.id||'new'}:${props.preset?.status||''}`+(draftScope?':'+draftScope:''),draft=recordDrafts[draftKey]
const saveBase=ref<Row|undefined>(draft?.base||(draft?.form?.version?draft.form:props.row))
const originalForm={id:crypto.randomUUID(),title:'',content:'',status:'TODO',plannedDate:'',dueDate:'',completedDate:'',result:'',important:false,tags:[],...props.row,...props.preset}
const form=reactive<Record<string,any>>(draft?.form||originalForm)
if(form.status==='DONE'&&!form.completedDate)form.completedDate=shanghaiDate()
const baseline=JSON.stringify(originalForm),closePrompt=ref(false)
const saving=ref(false),error=ref(''),submitted=ref(false),suggestions=ref<string[]>([])
const tagField = ref<InstanceType<typeof TagInput>>()
const dirty = computed(() => JSON.stringify(form) !== baseline || !!tagField.value?.text.trim())
const tags=computed(()=>Array.isArray(form.tags)?form.tags:[])
watch(()=>form.status,(value)=>{if(value==='DONE'&&!form.completedDate)form.completedDate=shanghaiDate();if(value!=='DONE')form.completedDate=''})
onMounted(async()=>{try{const f=await api<{tags:{name:string}[]}>('/journal/facets');suggestions.value=f.tags.map(t=>t.name)}catch{}})
onBeforeUnmount(() => {
  if (submitted.value || !state.authenticated) return
  if (dirty.value) {
    recordDrafts[draftKey] = {
      form: JSON.parse(JSON.stringify(form)),
      base: saveBase.value ? JSON.parse(JSON.stringify(saveBase.value)) : undefined,
      pendingTags: tagField.value?.text || ''
    }
  } else delete recordDrafts[draftKey]
})
function close(){if(saving.value)return;if(dirty.value)closePrompt.value=true;else emit('close')}
function discard(){submitted.value=true;delete recordDrafts[draftKey];emit('close')}
async function submit(){if(saving.value || tagField.value?.commit()===false)return;error.value='';if(!String(form.title||'').trim()){error.value='请填写准备做什么。';return}if(form.status==='DONE'&&(!form.completedDate||form.completedDate>shanghaiDate())){error.value='请填写今天或更早的实际完成日期。';return}saving.value=true;try{const fields=cleanTask(form);fields.tags=tags.value;if(form.status!=='DONE')fields.completedDate=null;const row=await saveRow('tasks',fields,saveBase.value);submitted.value=true;delete recordDrafts[draftKey];emit('saved',row,'tasks')}catch(e){error.value=(e as Error).message}finally{saving.value=false}}
</script>
<template><div class="overlay" @click.self="close"><section class="drawer task-editor" role="dialog" aria-modal="true" :aria-label="row?'编辑待办':'添加待办'"><header class="drawer-header"><div class="heading-with-icon"><CheckSquare2 :size="23"/><div><h2>{{row?'编辑待办':'添加待办'}}</h2></div></div><button class="icon-button" title="暂时关闭待办" :disabled="saving" @click="close"><X :size="20"/></button></header><form class="drawer-form" @submit.prevent="submit" @keydown.ctrl.enter.prevent="submit" @keydown.meta.enter.prevent="submit"><div class="drawer-body"><p class="hint">待办在全局保留，不随日志日期切换。安排日期和截止日期都可以留空。</p><p v-if="draft" class="hint">已恢复本次解锁期间的草稿。</p><div class="fields-grid"><label class="field full"><span>准备做什么 *</span><input v-model="form.title" required maxlength="500" placeholder="例如：完成项目 A 的接口联调" autofocus/></label><label class="field"><span>状态</span><select v-model="form.status" aria-label="状态"><option v-for="(label,key) in taskStatuses" :key="key" :value="key">{{label}}</option></select></label><label class="field important-field"><input v-model="form.important" type="checkbox"/><span>标记为重要</span></label><label class="field"><span>安排在哪天 · 选填</span><input v-model="form.plannedDate" type="date"/></label><label class="field"><span>截止日期 / Deadline · 选填</span><input v-model="form.dueDate" type="date"/></label><label class="field full"><span>任务说明</span><textarea v-model="form.content" rows="4" maxlength="10000" placeholder="准备怎么做，需要注意什么"/></label><template v-if="form.status==='DONE'"><label class="field full"><span>实际完成日期 *</span><input v-model="form.completedDate" type="date" :max="shanghaiDate()" required/></label><label class="field full"><span>完成结果 · 选填</span><textarea v-model="form.result" rows="3" maxlength="10000" placeholder="结果或交付内容"/></label><p class="hint full">实际完成日期会保留在完成历史中，关联的工作与学习记录继续累计投入。</p></template><label v-else-if="form.result" class="field full"><span>之前的结果说明</span><textarea v-model="form.result" rows="2" maxlength="10000"/></label></div><TagInput ref="tagField" :initial-text="draft?.pendingTags" :saved-values="saveBase?.tags || []" :disabled="saving" :model-value="tags" @update:model-value="form.tags=$event" label="标签" :suggestions="suggestions"/><p class="hint">暂时关闭后草稿保留在本次解锁期间，锁定或刷新页面后清除。</p><p v-if="error" class="error-box" role="alert">{{error}}</p></div><footer class="drawer-footer"><button type="button" class="button secondary" :disabled="saving" @click="close">暂时关闭</button><button class="button primary" :disabled="saving"><LoaderCircle v-if="saving" :size="16" class="spin"/><Check v-else :size="16"/>{{saving?'正在保存':form.status==='DONE'?'保存完成情况':'保存待办'}}</button></footer></form></section><div v-if="closePrompt" class="overlay centered" @click.self="closePrompt=false"><section class="modal draft-confirm" role="alertdialog" aria-modal="true" aria-label="保留未保存的草稿"><h2>这个待办还没有保存</h2><p>可以保留草稿后关闭，稍后继续。草稿仅保留在本次解锁期间。</p><footer><button class="button secondary" @click="closePrompt=false">继续填写</button><button class="button secondary" @click="discard">放弃草稿</button><button class="button primary" @click="emit('close')">保留并关闭</button></footer></section></div></div></template>
<style scoped>.draft-confirm{max-width:450px}.draft-confirm h2{font-size:18px}.draft-confirm p{font-size:12px;line-height:1.8}.draft-confirm footer{flex-wrap:wrap}.draft-confirm .button{font-size:11px;padding:9px 12px}.task-editor{width:min(520px,100vw)}.task-editor .drawer-header,.task-editor .drawer-body{padding:20px 23px}.task-editor .drawer-header h2{font-size:20px}.task-editor .drawer-footer{padding:14px 23px}.task-editor .field{margin-bottom:15px}.task-editor .field>span,.task-editor .hint{font-size:11px}.task-editor .hint{line-height:1.8}.task-editor .field input,.task-editor .field select,.task-editor .field textarea{font-size:12px;padding:10px 11px;min-height:40px}.task-editor .important-field{display:flex;align-items:center;gap:9px;padding-top:20px}.task-editor .important-field input{width:16px;min-height:16px}.task-editor .important-field span{margin:0}.task-editor .hint.full{grid-column:1/-1}@media(max-width:580px){.task-editor .drawer-header,.task-editor .drawer-body{padding:18px}.task-editor .drawer-footer{padding:12px 18px}}</style>
