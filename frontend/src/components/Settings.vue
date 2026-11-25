<script setup lang="ts">
import {ref,reactive} from 'vue'
import {Check,SlidersHorizontal,Download,Upload,Database,FileCheck,LoaderCircle,Sun,Moon,X} from 'lucide-vue-next'
import {state,api,saveSettings,refresh} from '../api'
import {localNow,collectionLabels} from '../types'
const emit=defineEmits<{changed:[];notify:[message:string]}>()
const preferenceFields=['theme','reducedMotion','reminderMode','quietStart','quietEnd','dailyReminderLimit','recentAlcoholHours']
const preferenceError=ref('')
const form=reactive(Object.fromEntries(preferenceFields.map(key=>[key,state.settings[key]]))),busy=ref(false),error=ref(''),backupFile=ref<any>(null),preview=ref<any>(null),backupName=ref(''),fileInput=ref<HTMLInputElement>(),restoreOpen=ref(false)
form.reminderMode = form.reminderMode === 'OFF' ? 'OFF' : 'GENTLE'
async function save(){busy.value=true;preferenceError.value='';try{await saveSettings(form);emit('notify','偏好已保存。');emit('changed')}catch(e){preferenceError.value=(e as Error).message}finally{busy.value=false}}
async function downloadBackup(){busy.value=true;error.value='';try{const data=await api('/backup');const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));a.download=`自记-完整备份-${localNow().replaceAll(':','-')}.json`;a.click();URL.revokeObjectURL(a.href);emit('notify','备份已下载，请妥善保管。')}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
async function selectBackup(event:Event){const file=(event.target as HTMLInputElement).files?.[0];if(!file)return;busy.value=true;error.value='';try{if(file.size>50*1024*1024)throw new Error('文件超过 50 MB，请先检查备份文件。');const data=JSON.parse(await file.text());const result=await api('/backup/preview',{method:'POST',body:JSON.stringify(data)});backupFile.value=data;backupName.value=file.name;preview.value=result;restoreOpen.value=true}catch(e){error.value=e instanceof SyntaxError?'文件不是有效的 JSON 备份，请重新选择。':(e as Error).message}finally{busy.value=false;if(fileInput.value)fileInput.value.value=''}}
async function restore(){busy.value=true;error.value='';try{await api('/backup/restore',{method:'POST',body:JSON.stringify({backup:backupFile.value,mode:'merge'})});await refresh();emit('changed');emit('notify','备份已合并恢复，数据已刷新。');restoreOpen.value=false;backupFile.value=null;preview.value=null}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
</script>
<template><div class="settings-page"><div class="settings-grid">
<section class="panel settings-section">
  <header><SlidersHorizontal :size="18"/><h3>界面与提醒</h3></header>
  <form @submit.prevent="save">
    <div class="settings-row"><strong>界面主题</strong><div class="segmented" role="group" aria-label="界面主题"><button type="button" :aria-pressed="form.theme==='dark'" :class="{active:form.theme==='dark'}" @click="form.theme='dark'"><Moon :size="15"/>深色</button><button type="button" :aria-pressed="form.theme==='light'" :class="{active:form.theme==='light'}" @click="form.theme='light'"><Sun :size="15"/>浅色</button></div></div>
    <label class="settings-row"><strong>减少动效</strong><input class="toggle" type="checkbox" v-model="form.reducedMotion"/></label>
    <label class="settings-row"><div><strong>显示温和提醒</strong><small>保存行为记录后，在页面内提醒</small></div><input class="toggle" type="checkbox" v-model="form.reminderMode" true-value="GENTLE" false-value="OFF"/></label>
    <div v-if="form.reminderMode!=='OFF'" class="quiet-fields">
      <label class="field"><span>安静时段开始</span><input type="time" v-model="form.quietStart"/></label>
      <label class="field"><span>安静时段结束</span><input type="time" v-model="form.quietEnd"/></label>
      <label class="field"><span>每日提醒上限</span><input type="number" v-model.number="form.dailyReminderLimit" min="0" max="20"/></label>
    </div>
    <label class="settings-row observation-row"><div><strong>饮酒关联观察窗口</strong><small>向前查找吸烟、私人行为之前的饮酒记录</small></div>
      <span><input type="number" v-model.number="form.recentAlcoholHours" min="1" max="168" aria-label="饮酒关联观察窗口（小时）"/> 小时</span></label>
    <p v-if="preferenceError" role="alert" class="error-box">{{preferenceError}}</p>
    <footer><button class="button primary" :disabled="busy"><Check :size="17"/>保存偏好</button></footer>
  </form>
</section>
<section class="panel settings-section backup-section">
  <header><Database :size="18"/><h3>备份与恢复</h3></header>
  <div class="backup-actions">
    <button class="backup-action" :disabled="busy" @click="downloadBackup"><Download :size="17"/><div><strong>导出完整备份</strong><span>下载带版本信息的 JSON 文件</span></div><span>导出</span></button>
    <button class="backup-action" :disabled="busy" @click="fileInput?.click()"><Upload :size="17"/><div><strong>导入并恢复备份</strong><span>预览新增记录与冲突，再决定是否合并</span></div><span>选择文件</span></button>
    <input ref="fileInput" class="visually-hidden" type="file" accept=".json,application/json" @change="selectBackup"/>
  </div>
  <p class="backup-note">完整备份包含个人数据，请保存在可信位置。</p>
  <p v-if="error" class="error-box" role="alert">{{error}}</p>
</section>
</div><div v-if="restoreOpen" class="overlay centered" @click.self="restoreOpen=false"><section class="modal wide-modal"><header><span class="icon-tile alcohol"><FileCheck :size="22"/></span><button class="icon-button" title="关闭" @click="restoreOpen=false"><X :size="20"/></button></header><h2>检查备份后再恢复</h2><p>{{backupName}}</p><div class="backup-summary"><div><strong>{{preview?.newRecords||0}}</strong><span>新增记录</span></div><div><strong>{{preview?.existingRecords||0}}</strong><span>已有记录</span></div><div><strong>{{preview?.conflicts?.length||0}}</strong><span>内容冲突</span></div></div><div class="table-scroll"><table><thead><tr><th>数据类别</th><th>新增</th><th>已有</th><th>冲突</th></tr></thead><tbody><tr v-for="(count,key) in preview?.counts||{}" :key="key"><td>{{collectionLabels[String(key)]||key}}</td><td>{{count.new}}</td><td>{{count.existing}}</td><td>{{count.conflicts}}</td></tr></tbody></table></div><p v-for="(notice,i) in preview?.warnings||[]" :key="`notice-${i}`" class="hint">{{notice}}</p><p v-for="(problem,i) in preview?.errors||[]" :key="i" class="error-box">{{problem}}</p><p class="hint">{{preview?.policy||'同编号的本机记录保持不变，只导入新的记录；恢复前自动保留当前备份。'}}</p><p v-if="error" class="error-box">{{error}}</p><footer><button class="button secondary" @click="restoreOpen=false">取消</button><button class="button primary" :disabled="busy||preview?.valid!==true" @click="restore"><LoaderCircle v-if="busy" :size="17" class="spin"/>确认合并恢复</button></footer></section></div></div></template>
<style scoped>
.settings-grid { align-items:start; grid-template-columns:minmax(0,1.3fr) minmax(0,1fr); gap:16px; }
.settings-grid .settings-section { min-width:0; padding:17px 19px; margin:0; }
.settings-section>header { margin:0; padding:0 0 12px; gap:8px; border-color:var(--border); }
.settings-section>header svg { color:var(--mint); }
.settings-section>header h3 { font-size:16px; margin:0; }
.settings-section .settings-row { gap:12px; margin:0; padding:12px 0; min-height:46px; border-color:var(--border); }
.settings-row strong,.backup-actions strong { color:var(--text); font-size:13px; font-weight:550; }
.settings-row small { color:var(--muted); font-size:11px; margin-top:4px; line-height:1.55; }
.settings-row .segmented button { padding:6px 8px; font-size:12px; }
.quiet-fields { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:8px; padding:9px 11px; border-radius:6px; background:var(--panel-soft); }
.quiet-fields .field { margin:0; min-width:0; }
.quiet-fields .field span { font-size:11px; color:var(--muted); }
.quiet-fields input { width:100%; min-width:0; padding:6px; font-size:12px; }
.observation-row>span { flex:none; color:var(--muted); font-size:12px; }
.observation-row input { width:56px; padding:6px; }
.settings-section form>footer { margin:0; padding-top:12px; border:0; }
.settings-grid .backup-actions { display:grid; grid-template-columns:1fr; gap:0; margin:0; }
.settings-section .backup-action { width:100%; min-width:0; text-align:left; padding:12px 0; gap:10px; border:0; border-bottom:1px solid var(--border); border-radius:0; background:transparent; }
.settings-section .backup-action>svg { color:var(--muted); }
.settings-section .backup-action:hover>svg { color:var(--mint); }
.backup-action div span,.settings-section .backup-note { color:var(--muted); font-size:11px; line-height:1.6; }
.settings-section .backup-action>span { color:var(--mint); font-size:12px; white-space:nowrap; }
.settings-section .backup-note { margin:11px 0 0; }
@media(max-width:950px) { .settings-grid { grid-template-columns:1fr; } }
@media(max-width:480px) {
  .settings-grid .settings-section { padding:14px; }
  .quiet-fields { grid-template-columns:repeat(2,minmax(0,1fr)); }
  .quiet-fields .field:last-child { grid-column:1 / -1; display:flex; align-items:center; justify-content:space-between; }
  .quiet-fields .field:last-child input { width:62px; }
}
</style>
