import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import {qaBase as base, qaPassword as password} from './qa-config.mjs';

let cookie = '', csrf = '', requests = 0;
async function request(path, {method = 'GET', body, status = 200} = {}) {
  const headers = {Cookie: cookie};
  if (method !== 'GET') Object.assign(headers, {'Content-Type': 'application/json', 'X-CSRF-Token': csrf});
  const response = await fetch(base + path, {method, headers, body: body && JSON.stringify(body)});
  const cookies = response.headers.getSetCookie();
  if (cookies.length) cookie = cookies.map(value => value.split(';')[0]).join('; ');
  assert.equal(response.status, status, `${method} ${path}`);
  requests++;
  return response.json();
}

function shiftDay(date, delta) {
  const day = new Date(date + 'T00:00:00Z');
  day.setUTCDate(day.getUTCDate() + delta);
  return day.toISOString().slice(0, 10);
}
function expectedNumber(actual, expected, name) {
  if (expected === null) return assert.equal(actual, null, name);
  assert.equal(typeof actual, 'number', name);
  assert.ok(Math.abs(actual - expected) <= 0.00501, `${name}: ${actual} != ${expected}`);
}
const at = row => String(row.occurredAt || row.startedAt || '');
const dayOf = row => at(row).slice(0, 10);
const sum = (rows, key) => rows.reduce((n, row) => n + Number(row[key] ?? 0), 0);
const missing = (rows, key) => rows.filter(row => row[key] == null).length;
function knownSum(rows, key, emptySessions = 0) {
  const known = rows.filter(row => row[key] != null);
  return known.length === 0 && rows.length + emptySessions > 0 ? null : sum(known, key);
}

csrf = (await request('/auth/status')).csrfToken;
await request('/analytics/summary', {status: 401});
csrf = (await request('/auth/login', {method: 'POST', body: {password}})).csrfToken;
let result;
try {
  const {collections} = await request('/bootstrap');
  const lifetime = await request('/analytics/summary');
  const now = new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
  }).format(new Date()).replace(' ', 'T');
  const today = now.slice(0, 10);
  assert.equal(lifetime.to, today);
  assert.ok(!('daily' in lifetime) && !('links' in lifetime));
  const kinds = ['smoking', 'sessions', 'drinks', 'intimacy'];
  const rows = Object.fromEntries(kinds.map(kind => [kind,
    (collections[kind] || []).filter(row => !row.deleted && at(row) && at(row) <= now)
  ]));
  const populatedSessions = new Set(rows.drinks.map(row => row.sessionId));
  const dates = [...new Set(kinds.flatMap(kind => rows[kind].map(dayOf)))].sort();
  assert.equal(lifetime.from, dates[0] || null, 'Earliest historical behavior');

  function verifySummary(summary, from, to, {daily = false, keys} = {}) {
    const between = kind => rows[kind].filter(row => dayOf(row) >= from && dayOf(row) <= to);
    const smoking = between('smoking'), sessions = between('sessions');
    const drinks = between('drinks'), intimacy = between('intimacy');
    const emptySessions = sessions.filter(row => !populatedSessions.has(row.id)).length;
    const products = [...smoking, ...drinks];
    const grams = knownSum(drinks, 'ethanolGrams', emptySessions);
    const quantity = items => items.reduce((n, row) => n + Number(row.count ?? 1), 0);
    const measures = {
      smokingCount: sum(smoking, 'count'), alcoholSessions: sessions.length,
      alcoholMl: knownSum(drinks, 'actualMl', emptySessions), ethanolGrams: grams,
      ethanolEquivalent42Ml: grams === null ? null : grams / (0.42 * 0.789),
      alcoholUnknownVolumeCount: missing(drinks, 'actualMl') + emptySessions,
      alcoholUnknownEthanolCount: missing(drinks, 'ethanolGrams') + emptySessions,
      intimacyCount: quantity(intimacy),
      sexCount: quantity(intimacy.filter(row => row.activityType === 'SEX')),
      masturbationCount: quantity(intimacy.filter(row => row.activityType !== 'SEX')),
      consumptionCost: knownSum(products, 'cost', emptySessions),
      smokingUnknownCostCount: missing(smoking, 'cost'),
      alcoholUnknownCostCount: missing(drinks, 'cost') + emptySessions,
      unknownCostCount: missing(products, 'cost') + emptySessions
    };
    for (const name of keys ?? Object.keys(measures)) {
      expectedNumber(summary[name], measures[name], `${from}..${to} ${name}`);
    }
    if (daily) {
      assert.equal(summary.hasSmokingRecords, smoking.length > 0);
      assert.equal(summary.hasAlcoholRecords, sessions.length + drinks.length > 0);
      assert.equal(summary.hasIntimacyRecords, intimacy.length > 0);
      assert.equal(summary.isFuture, false);
    }
  }
  async function verifyRange(from, to) {
    const report = await request(`/analytics?from=${from}&to=${to}`);
    assert.equal(report.from, from);
    assert.equal(report.to, to);
    const expectedDates = [];
    for (let day = from; day <= to; day = shiftDay(day, 1)) expectedDates.push(day);
    assert.deepEqual(report.daily.map(day => day.date), expectedDates, 'Contiguous days, oldest first');
    verifySummary(report.totals, from, to);
    for (const day of report.daily) verifySummary(day, day.date, day.date, {daily: true});
    return report;
  }

  verifySummary(lifetime.totals, lifetime.from || today, today);
  const {unit, points} = lifetime.trend;
  assert.ok(['day', 'month', 'year'].includes(unit), 'Lifetime trend returns a known grouping');
  assert.ok(Array.isArray(points), 'Lifetime trend returns dated points');
  if (lifetime.from) {
    const first = new Date(lifetime.from + 'T00:00:00Z');
    const last = Date.parse(today);
    const days = (last - first.getTime()) / 86400000 + 1;
    const anniversary = new Date(first);
    anniversary.setUTCFullYear(first.getUTCFullYear() + 30);
    if (anniversary.getUTCMonth() !== first.getUTCMonth()) anniversary.setUTCDate(0);
    assert.equal(unit, days <= 731 ? 'day' : today > anniversary.toISOString().slice(0, 10) ? 'year' : 'month');

    const starts = [];
    const cursor = new Date(first);
    while (cursor.getTime() <= last) {
      starts.push(cursor.toISOString().slice(0, 10));
      if (unit === 'day') cursor.setUTCDate(cursor.getUTCDate() + 1);
      if (unit === 'month') cursor.setUTCMonth(cursor.getUTCMonth() + 1, 1);
      if (unit === 'year') cursor.setUTCFullYear(cursor.getUTCFullYear() + 1, 0, 1);
    }
    assert.deepEqual(points.map(point => point.date), starts, 'Every period from the first record through today, including gaps');
    for (const [index, point] of points.entries()) {
      const end = starts[index + 1] ? shiftDay(starts[index + 1], -1) : today;
      verifySummary(point, point.date, end, {keys: ['smokingCount', 'ethanolEquivalent42Ml', 'intimacyCount']});
    }
  } else {
    assert.deepEqual(points, [], 'Empty history has no artificial trend points');
  }
  const week = await verifyRange(shiftDay(today, -6), today);
  const month = await verifyRange(shiftDay(today, -29), today);
  assert.deepEqual(month.daily.slice(-7), week.daily, 'Changing range preserves the same seven daily summaries');

  const monthStart = today.slice(0, 7) + '-01';
  const previousEnd = shiftDay(monthStart, -1);
  const previousStart = previousEnd.slice(0, 7) + '-01';
  const previousMonth = await verifyRange(previousStart, previousEnd);
  const currentMonth = await verifyRange(monthStart, today);
  const boundary = await verifyRange(previousEnd, monthStart);
  assert.deepEqual(boundary.daily, [previousMonth.daily.at(-1), currentMonth.daily[0]], 'Month navigation boundary');
  assert.deepEqual((await request('/analytics/summary')).trend, lifetime.trend, 'Calendar navigation preserves the full trend');

  const occupied = new Set(dates);
  const emptyDate = month.daily.find(day => !occupied.has(day.date))?.date || shiftDay(dates[0], -1);
  const missingProduct = [...rows.smoking, ...rows.drinks].find(row => row.cost == null);
  const missingDrink = rows.drinks.find(row => row.actualMl == null || row.ethanolGrams == null);
  const emptySession = rows.sessions.find(row => !populatedSessions.has(row.id));
  const detailDates = new Set([emptyDate, dates[0], dates.at(-1),
    ...[missingProduct, missingDrink, emptySession,
        rows.intimacy.find(row => row.activityType === 'SEX'),
        rows.intimacy.find(row => row.activityType !== 'SEX')].filter(Boolean).map(dayOf)
  ].filter(Boolean));
  for (const date of detailDates) {
    const detail = await verifyRange(date, date);
    const dayInRange = [...month.daily, ...previousMonth.daily].find(day => day.date === date);
    if (dayInRange) assert.deepEqual(detail.daily[0], dayInRange, 'Daily drilldown agrees with range');
  }
  result = {passed: true, weekFrom: week.from, monthFrom: month.from, weekTo: today,
    lifetimeFrom: lifetime.from, trendUnit: unit, trendPoints: points.length,
    checkedDays: detailDates.size, checkedPopulatedDay: dates.length > 0,
    checkedEmptyDay: true, verifiedAt: new Date().toISOString()};
} finally {
  await request('/auth/logout', {method: 'POST'});
}
await writeFile(new URL('../.local/weekly-result.json', import.meta.url), JSON.stringify({...result, requests}, null, 2));
console.log(`PASS: ${requests} read-only QA requests; full-history trend (${result.trendPoints} ${result.trendUnit} points), 7/30-day and lifetime totals, month navigation, dated details and zero days verified.`);
