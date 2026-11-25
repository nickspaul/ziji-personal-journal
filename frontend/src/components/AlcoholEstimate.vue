<script setup lang="ts">
import {computed} from 'vue'
import {Info,ChevronRight,Wine,ArrowRight} from 'lucide-vue-next'
import {state} from '../api'
import {number,shortDate} from '../types'
const emit=defineEmits<{view:[page:string];repair:[detail:Record<string,any>]}>()
const alcohol=computed(()=>state.analytics.alcohol||{})
const issues=computed(()=>alcohol.value.missingDetails||[])
const historyIssues=computed(()=>alcohol.value.historicalMissingDetails||[])
const warnings=computed(()=>alcohol.value.warnings||[])
function action(d:Record<string,any>){return d.collection==='body'?'补充身体数据':d.collection==='sessions'?'补充这场酒品':'补充饮用明细'}
function issueDate(d:Record<string,any>){return d.occurredAt?shortDate(d.occurredAt):''}
const duration=computed(()=>alcohol.value.durationHoursLow==null?'—':`${number(alcohol.value.durationHoursLow,0)}–${number(alcohol.value.durationHoursHigh,0)} 小时`)
const bodySources=computed(()=>{const rows=alcohol.value.bodyMeasurements||[];return [...new Map(rows.map((b:any)=>[b.id,b])).values()] as Record<string,any>[]})
</script>
<template><section class="panel alcohol-reference">
<header class="panel-header"><div><h3>酒精估算与计算依据</h3></div><button class="text-link" @click="emit('view','body')">身体数据 <ChevronRight :size="15"/></button></header>
<div v-if="alcohol.status==='ESTIMATE'" class="estimate-result sensitive"><span>本组饮酒的模型时长</span><strong>{{duration}}</strong><p>{{alcohol.durationFrom||'从最后饮用的可能时间起算，按整小时展示情景范围。'}}</p><small v-if="alcohol.alreadyBeyondEstimate">这段已记录饮酒的模型时段已过，不代表身体已恢复或酒精已实际清零。</small><small v-else>时间按大致时段参与估算；先后不明时，不强行关联。</small></div>
<div v-else-if="alcohol.status==='INSUFFICIENT_DATA'" class="estimate-missing"><strong>这次还缺这些资料</strong><p v-if="!issues.length">{{(alcohol.missing||[]).join('；')}}</p><div v-for="(d,i) in issues" :key="i" class="repair-line"><div><small v-if="issueDate(d)" class="sensitive">{{issueDate(d)}}</small><span>{{d.message}}</span></div><button class="text-link" @click="emit('repair',d)">{{action(d)}} <ArrowRight :size="14"/></button></div></div>
<div v-else class="estimate-empty"><Wine :size="23"/><p>还没有可供估算的饮酒记录。</p><button class="text-link" @click="emit('view','manager/sessions')">查看记录</button></div>
<div v-if="warnings.length" class="estimate-warnings"><p v-for="(warning,i) in warnings" :key="i">{{warning}}</p></div>
<p v-if="alcohol.scope" class="scope-note sensitive">{{alcohol.scope.label}}<template v-if="alcohol.scope.from"> · {{shortDate(alcohol.scope.from)}}—{{shortDate(alcohol.scope.to)}}</template></p>
<div class="soft-notice"><Info :size="16"/><span>这是按已录入资料计算的粗略情景范围，不能确认实际清零、身体恢复或何时能驾驶。</span></div>
<details v-if="alcohol.historicalMissingCount" class="detail-block"><summary>较早的 {{alcohol.historicalMissingCount}} 项资料待补，不阻挡本次展示</summary><p class="muted small">这些记录未被当作零饮酒。当前估算范围只是展示选择，不是身体代谢的界限。</p><div v-for="(d,i) in historyIssues" :key="i" class="repair-line"><span class="sensitive">{{issueDate(d)}} {{d.message}}</span><button class="text-link" @click="emit('repair',d)">{{action(d)}}</button></div></details>
<details class="detail-block calculation-explained"><summary>这是怎么算的？</summary><ol><li>按本人喝的毫升数 × 酒精度 × 0.789，换算纯酒精克数。</li><li>用身高、体重、年龄及模型所需性别参数估算体水量，再换算模型浓度。</li><li>组合不同吸收、消除速度和饮用时间，得到一组参考情景，时长向外取整到小时。</li></ol><p>大概时间按前后约 1 小时计算；只记得日期时覆盖整天。吸烟和私人记录的先后顺序无法确定时，会保留不确定，不强行判定酒后。</p><p v-for="b in bodySources" :key="b.id" class="sensitive">采用 {{shortDate(b.measuredAt)}} 的身体测量{{b.selection==='LATER_APPROXIMATION'?'（饮酒之后补测，作为近似）':''}}。</p><p v-for="(a,i) in alcohol.assumptions||[]" :key="i" class="muted small">{{a}}</p><p class="muted small">模型：{{alcohol.modelVersion||'等待计算'}}</p><details><summary>原始输入、参数与来源</summary><pre class="json-detail sensitive">{{JSON.stringify(alcohol,null,2)}}</pre></details></details>
</section></template>
<style scoped>
.alcohol-reference{padding-bottom:22px;min-width:0}.alcohol-reference>.panel-header{margin-bottom:8px}.estimate-result,.estimate-missing,.estimate-empty,.estimate-warnings,.scope-note,.calculation-explained,.alcohol-reference>.detail-block{margin:16px 24px}.estimate-result>span{font-size:12px;color:var(--muted)}.estimate-result>strong{display:block;font-size:29px;font-weight:500;color:#acbafa;margin:9px 0}.estimate-result p,.estimate-result small,.scope-note,.estimate-warnings p{font-size:12px;line-height:1.7;color:var(--muted)}.estimate-result small{display:block}.estimate-missing>strong{font-size:14px;font-weight:500}.repair-line{display:flex;justify-content:space-between;gap:14px;align-items:center;border-bottom:1px solid var(--border);padding:13px 0;font-size:12px}.repair-line>div{display:grid;gap:4px}.repair-line small{color:var(--muted)}.repair-line button{flex-shrink:0;font-size:12px}.alcohol-reference>.soft-notice{margin:16px 24px;font-size:12px}.estimate-empty{display:flex;align-items:center;gap:12px;font-size:13px;flex-wrap:wrap}.estimate-warnings p{margin:6px 0}.calculation-explained{font-size:13px;line-height:1.8}.calculation-explained ol{padding-left:20px}@media(max-width:600px){.repair-line{flex-wrap:wrap}.alcohol-reference>.panel-header{align-items:flex-start}}
</style>
