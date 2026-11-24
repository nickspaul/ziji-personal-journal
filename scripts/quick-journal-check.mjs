import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from '../frontend/node_modules/typescript/lib/typescript.js'

const source = await readFile(new URL('../frontend/src/quickJournal.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
const { parseQuickJournal } = await import('data:text/javascript;base64,' + Buffer.from(compiled.outputText).toString('base64'))
const parse = line => parseQuickJournal(line, '2026-01-01', '2025-12-14', ['小林', '林', '小陈'], ['东门', '东门食堂'])
const meal = parse('昨晚和小林在东门吃烧烤，我付了 86 元')
assert.deepEqual([meal.date, meal.period, meal.place, meal.people, meal.category, meal.expenseCategory, meal.amount],
                 ['2025-12-31', 'EVENING', '东门', ['小林'], 'MEAL', '餐饮', '86'])
for (const line of [
  '和小林吃饭，一共 86 元', '我付了86美元', '我付了 86 美元', '我付了86元左右', '我付了86元？',
  '小林替我付了86元', '不是我付了86元', '我需要付86元', '我花了 2 小时', '我付了10元，我付了20元',
  '我付了2.345元', '我付了0元', '我付了-6元', '我付了20万', '我们付了65元', '人均32元', '我付了1,20元',
]) assert.equal(parse(line).amount, '', line)
assert.equal(parse('一共 129 元，我实付 43.50 元').amount, '43.50')
assert.equal(parse('本人支付￥20').amount, '20')
assert.equal(parse('我支付了1,200.50元').amount, '1200.50')
assert.equal(parse('昨晚在东门食堂吃饭').place, '东门食堂')
assert.equal(parse('前天在科技馆看电影').place, '科技馆')
assert.equal(parse('前天在科技馆看电影').date, '2025-12-30')
assert.equal(parse('上周日爬山').date, '2025-12-28')
assert.equal(parse('12月31日上课').date, '2025-12-31')
assert.equal(parse('2024.02.29上课').date, '2024-02-29')
assert.equal(parse('2025-02-29上课').date, '')
assert.equal(parse('下周聚会').date, '')
assert.equal(parse('去自习').date, '2025-12-14')
assert.deepEqual(parse('和小陈、小林碰面 #出差 #复盘').people, ['小陈','小林'])
assert.deepEqual(parse('和小陈、小林碰面 #出差 #复盘').tags, ['出差','复盘'])
console.log('PASS: Chinese quick-entry parsing, date boundaries and ambiguous payments.')

if (process.argv.includes('--api')) {
  const { qaBase, qaPassword } = await import('./qa-config.mjs')
  let cookie = '', csrf = ''
  async function request(path, method = 'GET', body, expected = 200) {
    const response = await fetch(qaBase + path, {
      method, headers: { Cookie: cookie, 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
      body: body == null ? undefined : JSON.stringify(body),
    })
    const cookies = response.headers.getSetCookie()
    if (cookies.length) cookie = cookies.map(value => value.split(';')[0]).join('; ')
    const result = await response.json()
    assert.equal(response.status, expected, path + ' ' + JSON.stringify(result))
    return result
  }
  const auth = await request('/auth/status')
  csrf = auth.csrfToken
  const login = await request('/auth/' + (auth.configured ? 'login' : 'setup'), 'POST', { password: qaPassword })
  csrf = login.csrfToken
  const path = '/journal/quick-entry', requestId = crypto.randomUUID(), title = '一句话补记测试-' + requestId
  const journal = { title, category:'MEAL', startDate:'2025-12-31', timeMode:'PERIOD', period:'EVENING', people:['合成同行人'] }
  const ledger = { date:'2025-12-31', direction:'EXPENSE', amount:'86.50', category:'餐饮测试', notes:title }
  const payload = { requestId, journal, ledger }
  await request(path, 'POST', { ...payload, ledger:{ ...ledger, amount:-1 } }, 400)
  const query = '/journal/query?collection=journal&q=' + encodeURIComponent(title)
  assert.equal((await request(query)).total, 0, 'invalid ledger must roll back journal')
  const [first, retry] = await Promise.all([request(path, 'POST', payload), request(path, 'POST', payload)])
  assert.equal(first.journal.id, retry.journal.id)
  assert.equal(first.ledger.id, retry.ledger.id)
  assert.equal((await request(query)).total, 1)
  assert.ok((await request(path + '/expense-categories')).includes('餐饮测试'))
  await request(path, 'POST', { ...payload, ledger:{...ledger, amount:'55'} }, 409)
  const loneId = crypto.randomUUID()
  const loneEntry = await request(path, 'POST', { requestId:loneId, ledger })
  assert.ok(loneEntry.ledger && !loneEntry.journal)
  await request(path, 'POST', { requestId:loneId, journal, ledger }, 409)
  assert.equal((await request(query)).total, 1, 'new selection cannot extend an already saved request')
  const onlyLog = await request(path, 'POST', { requestId:crypto.randomUUID(), journal })
  assert.ok(onlyLog.journal && !onlyLog.ledger)
  await request(path, 'POST', { requestId:crypto.randomUUID() }, 400)
  for (const [collection, row] of [['ledger',first.ledger], ['journal',first.journal], ['ledger',loneEntry.ledger], ['journal',onlyLog.journal]])
    await request('/data/'+collection+'/'+row.id+'?version='+row.version, 'DELETE')
  await request('/auth/logout','POST')
  console.log('PASS: atomic creation, single selections, retry and conflicting requests on isolated QA database.')
}
