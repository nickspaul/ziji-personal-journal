<script setup lang="ts">
import { computed, ref } from 'vue'
import { Plus, X } from 'lucide-vue-next'

const props = withDefaults(defineProps<{
  modelValue: string[]
  suggestions?: string[]
  savedValues?: string[]
  initialText?: string
  label: string
  placeholder?: string
  tags?: boolean
  disabled?: boolean
  maxItems?: number
}>(), { suggestions: () => [], savedValues: () => [], tags: true, disabled: false, maxItems: 30 })
const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()
const text = ref(props.initialText || ''), error = ref(''), removing = ref('')
const input = ref<HTMLInputElement>(), confirmDialog = ref<HTMLDialogElement>()
const inputId = 'tokens-' + crypto.randomUUID()
const kind = computed(() => props.tags ? '标签' : '同行人')

function normalize(value: string) {
  const trimmed = value.trim()
  return props.tags ? trimmed.replace(/^#+/, '').trim().toLowerCase() : trimmed
}

const candidates = computed(() => {
  const query = normalize(text.value).toLowerCase()
  const chosen = new Set(props.modelValue)
  return props.suggestions.filter(item => !chosen.has(item) && item.toLowerCase().includes(query))
})

function add(values: string[], focusInvalid: boolean) {
  if (props.disabled) return false
  const next = new Set(props.modelValue)
  for (const value of values.map(normalize).filter(Boolean)) {
    if (value.length > 80 || /[\u0000-\u001f\u007f-\u009f]/u.test(value)) {
      error.value = '每个名称最多 80 个字符，请去掉换行或控制字符。'
      if (focusInvalid) input.value?.focus()
      return false
    }
    next.add(value)
  }
  if (next.size > props.maxItems) {
    error.value = `最多可选 ${props.maxItems} 项，请先移除不需要的${kind.value}。`
    if (focusInvalid) input.value?.focus()
    return false
  }
  if (next.size !== props.modelValue.length) emit('update:modelValue', [...next])
  error.value = ''
  text.value = ''
  return true
}

function commit(focusInvalid = true) {
  if (!text.value.trim()) return true
  return add(text.value.split(/[,，;；\n]+/), focusInvalid)
}

function choose(value: string, event: MouseEvent) {
  if (add([value], true) && event.detail === 0) input.value?.focus()
}

function leaveField(event: FocusEvent) {
  const next = event.relatedTarget
  // The next control may be one of our suggestions. Its click commits the full name.
  if (next instanceof Node && !(event.currentTarget as HTMLElement).contains(next)) commit(false)
}

function inputKey(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) {
    event.stopPropagation()
    return
  }
  if (event.ctrlKey || event.metaKey || event.altKey) return
  if (['Enter', ',', '，', ';', '；'].includes(event.key)) {
    event.preventDefault()
    commit()
  }
}

function drop(value: string) {
  emit('update:modelValue', props.modelValue.filter(item => item !== value))
  error.value = ''
}

function requestRemoval(value: string) {
  if (props.savedValues.includes(value)) {
    removing.value = value
    confirmDialog.value?.showModal()
  } else drop(value)
}

function finishRemoval(confirmed: boolean) {
  confirmDialog.value?.close()
  if (confirmed) {
    drop(removing.value)
    input.value?.focus()
  }
  removing.value = ''
}

defineExpose({ commit, text })
</script>

<template>
  <div class="token-field" @focusout="leaveField">
    <label :for="inputId">{{ label }}</label>
    <div class="token-box">
      <span v-for="value in modelValue" :key="value" class="token sensitive">
        {{ tags ? '#' : '' }}{{ value }}
        <button type="button" :aria-label="'移除'+value" :disabled="disabled" @click="requestRemoval(value)"><X :size="12"/></button>
      </span>
      <input :id="inputId" ref="input" v-model="text" type="text" autocomplete="off" :disabled="disabled"
        :placeholder="placeholder || (tags ? '搜索或输入标签，回车添加' : '搜索或输入姓名，回车添加')"
        :aria-invalid="!!error" :aria-describedby="error ? inputId+'-error' : undefined"
        @input="error=''" @keydown="inputKey"/>
      <button v-if="text.trim()" type="button" :aria-label="'添加'+kind" :disabled="disabled" @mousedown.prevent @click="commit()"><Plus :size="15"/></button>
    </div>
    <div v-if="candidates.length" class="token-suggestions sensitive" role="group" :aria-label="'可选'+kind">
      <button v-for="item in candidates" :key="item" type="button" :disabled="disabled"
        @mousedown.prevent @click="choose(item, $event)">{{ tags ? '#' : '' }}{{ item }}</button>
    </div>
    <p v-if="error" :id="inputId+'-error'" class="token-error" role="alert">{{ error }}</p>
    <dialog ref="confirmDialog" class="token-confirm" :aria-labelledby="inputId+'-confirm'"
      @cancel.prevent="finishRemoval(false)" @keydown.stop>
      <h3 :id="inputId+'-confirm'">从这条记录移除{{ kind }}？</h3>
      <p class="sensitive">保存后，这条记录将移除「{{ removing }}」。</p>
      <div class="confirm-actions">
        <button type="button" class="button secondary" autofocus @click="finishRemoval(false)">保留</button>
        <button type="button" class="button primary" @click="finishRemoval(true)">确认移除</button>
      </div>
    </dialog>
  </div>
</template>

<style scoped>
.token-field { min-width: 0; margin: 0 0 15px; }
.token-field > label { display: block; font-size: 11px; color: var(--muted); margin-bottom: 8px; }
.token-box { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; min-height: 41px; border: 1px solid var(--border); border-radius: 8px; padding: 7px 9px; background: var(--input); }
.token-box:focus-within { border-color: var(--mint); }
.token { display: inline-flex; align-items: center; gap: 5px; background: var(--mint-bg); border: 1px solid var(--border); border-radius: 5px; color: var(--mint); font-size: 11px; padding: 4px 6px; max-width: 100%; overflow-wrap: anywhere; }
.token-box button { display: inline-flex; border: 0; background: none; color: inherit; padding: 2px; cursor: pointer; }
.token-box input { width: 140px; flex: 1; min-width: 90px; background: none; border: 0; outline: none; color: var(--text); font: inherit; font-size: 12px; padding: 3px; }
.token-suggestions { display: flex; align-content: start; flex-wrap: wrap; gap: 5px; max-height: 9rem; overflow-y: auto; margin-top: 6px; padding: 2px; }
.token-suggestions button { max-width: 100%; min-height: 28px; padding: 4px 8px; border: 1px solid var(--border); border-radius: 5px; background: var(--panel-soft); color: var(--text); font-size: 11px; overflow-wrap: anywhere; }
.token-suggestions button:hover, .token-suggestions button:focus-visible { border-color: var(--mint); }
.token-error { margin: 5px 0 0; color: var(--pink); font-size: 12px; }
.token-confirm { max-width: min(360px, calc(100vw - 24px)); padding: 18px; border: 1px solid var(--border); border-radius: 12px; background: var(--panel); color: var(--text); box-shadow: var(--shadow); }
.token-confirm::backdrop { background: #070b11a8; }
.token-confirm h3 { margin: 0; font-size: 16px; }
.token-confirm p { margin: 9px 0 16px; color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
.confirm-actions { display: flex; gap: 6px; justify-content: flex-end; }
</style>
