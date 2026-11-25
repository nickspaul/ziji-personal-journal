<script setup lang="ts">
import {computed,ref} from 'vue'
import {CreditCard,X} from 'lucide-vue-next'
import {debtAccounts} from '../ledgerReview'
import type {DebtKind,LedgerRow} from '../ledgerReview'
import {money,shanghaiDate} from '../types'

const props=defineProps<{rows:LedgerRow[];ready:boolean}>()
const emit=defineEmits<{entry:[kind:DebtKind,platform?:string,row?:LedgerRow];history:[platform?:string]}>()
const dialog=ref<HTMLDialogElement>()
const today=ref(shanghaiDate())
const accounts=computed(()=>debtAccounts(props.rows,today.value))
const total=computed(()=>accounts.value.reduce((sum,a)=>sum+a.balance,0))
const format=(n:number)=>money(n/100)
function show(){today.value=shanghaiDate();dialog.value?.showModal()}
function entry(kind:DebtKind,platform?:string,row?:LedgerRow){dialog.value?.close();emit('entry',kind,platform,row)}
function history(platform?:string){dialog.value?.close();emit('history',platform)}
</script>
<template>
  <button class="button secondary compact-button" :disabled="!ready" @click="show"><CreditCard :size="16"/>平台负债 <strong v-if="total" class="sensitive">{{format(total)}}</strong></button>
  <dialog ref="dialog" class="debt-dialog" aria-labelledby="debt-title" @click.self="dialog?.close()">
    <header><div><h2 id="debt-title">平台负债</h2><small>截至 {{today}} · 各平台未还本金</small></div><button type="button" class="icon-button" aria-label="关闭" title="关闭" @click="dialog?.close()"><X :size="18"/></button></header>
    <p class="debt-total sensitive">{{format(total)}}</p>
    <div class="debt-actions"><button class="button primary compact-button" @click="entry('CREDIT')">记信用消费</button><button class="button secondary compact-button" @click="entry('REPAYMENT')">记还款</button><button class="text-link" @click="entry('OPENING')">录期初欠款</button></div>
    <ul v-if="accounts.length" class="debt-accounts sensitive">
      <li v-for="a in accounts" :key="a.platform"><div><strong>{{a.platform}}</strong><strong>{{format(a.balance)}}</strong></div>
        <div class="account-tools"><button class="text-link" @click="history(a.platform)">查看流水</button>
          <button class="text-link" @click="entry('OPENING',a.platform,a.opening)">{{a.opening?'编辑期初':'录期初'}}</button>
          <button v-if="a.balance>0" class="text-link" @click="entry('REPAYMENT',a.platform)">记还款</button></div></li>
    </ul>
    <p v-else class="explain">第一次使用时，已有欠款可录期初本金；今天新买的东西直接记信用消费。</p>
    <p class="explain">已经记过购买或扣款？编辑原流水，把付款方式改为「信用支付」，或把扣款改为「还款」。<button class="text-link" @click="history()">查找已有流水</button></p>
  </dialog>
</template>
<style scoped>
.debt-dialog{box-sizing:border-box;margin:auto;width:min(460px,calc(100% - 24px));max-height:84dvh;overflow:auto;padding:18px;border:1px solid var(--border);border-radius:14px;background:var(--panel);color:var(--text)}.debt-dialog::backdrop{background:#070b11a8}.debt-dialog header,.debt-actions,.debt-accounts li>div{display:flex;align-items:center;gap:8px}.debt-dialog header,.debt-accounts li>div{justify-content:space-between}.debt-dialog h2{font-size:18px;margin:0 0 4px}.debt-dialog small,.explain{color:var(--muted);font-size:12px;line-height:1.7}.debt-dialog .debt-total{font-size:27px;font-weight:600;margin:12px 0 8px}.debt-actions,.account-tools{flex-wrap:wrap}.debt-actions .text-link,.debt-dialog .text-link{font-size:12px}.debt-accounts{list-style:none;padding:0;margin:14px 0}.debt-accounts li{padding:9px 0;border-top:1px solid var(--border)}.debt-accounts li>div:first-child{font-size:14px}.debt-accounts li>.account-tools{justify-content:flex-end;gap:13px;margin-top:4px}.debt-dialog .explain{margin:13px 0 0}.explain button{margin-left:5px}
</style>
