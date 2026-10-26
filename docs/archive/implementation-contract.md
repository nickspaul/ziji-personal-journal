# Implementation contract

Application paths are relative to the project root. Java 17, Spring Boot 3.5.x, Vue 3 + TypeScript + Vite + ECharts, MySQL 8.0. Backend port 17890 (loopback only). Frontend dev port 5173. Use Maven module `backend/`, frontend `frontend/`, Windows scripts `scripts/`. No public deployment.

## API

- GET `/api/auth/status` -> `{configured:boolean,authenticated:boolean,csrfToken:string}`. Session cookie. Fetch this before writes and send `X-CSRF-Token`. POST `/api/auth/setup` or `/api/auth/login` `{password}`. POST `/api/auth/logout`.
- GET `/api/bootstrap` -> `{collections:{[name]: Row[]},settings: object,schema: CollectionSchema[],health: object[]}`.
- POST `/api/data/{collection}` body flat fields + optional `id` (UUID, idempotent create). PUT `/api/data/{collection}/{id}` fields + `version`. DELETE same URL `?version=N` soft delete. POST `.../{id}/restore` `{version}`.
- GET `/api/data/{collection}?deleted=true` -> rows in trash; GET without flag -> active rows.
- PUT `/api/settings` JSON settings (merge).
- GET `/api/analytics?from=YYYY-MM-DD&to=YYYY-MM-DD` -> `{daily:[],totals:{},alcohol:{},links:{},warnings:[]}`. Details negotiated with calculation module; frontend can gracefully calculate display totals from already backend-enriched rows.
- GET `/api/backup` -> JSON download. POST `/api/backup/preview` `{...backup}` -> counts/conflicts. POST `/api/backup/restore` `{backup,mode:"merge"}` transactionally imports validated records (creates pre-restore backup). GET `/api/audit`.
- GET `/api/catalog/search?q=...` -> source candidates; POST `/api/catalog/import` `{candidate,...}` imports after explicit UI preview. Root implements catalog adapter.
- Errors: `{message:string}` with HTTP 400/401/403/409/500. No secret output.

## Row shape

Flat object `{id:string,version:number,createdAt:string,updatedAt:string,deleted:boolean,...fields}`. Local timestamps ISO `YYYY-MM-DDTHH:mm:ss`; dates `YYYY-MM-DD`, timezone Asia/Shanghai. No invented personal history. Empty database starts without behavior records.

Collections and camelCase fields:

- products: kind `SMOKING|ALCOHOL`, brand, name, variant, aliases, barcode, unitCount (cigarettes per pack), bottleMl, abv, tarMg, nicotineMg, coMg, constituentMethod, sourceUrl, sourceDate, verified:boolean, favorite:boolean, locked:boolean, active:boolean, notes.
- prices: productId, amount (per pack/bottle), priceType `ACTUAL|PERSONAL|REFERENCE`, effectiveDate, currency `CNY`, channel, sourceUrl, verified, notes.
- purchases: productId, quantity (packs/bottles), amount (total payment), occurredAt, payer `SELF|OTHER`, notes.
- smoking: occurredAt, timePrecision `EXACT|DATE`, productId (optional), count, source `SELF|GIFT|OTHER`, purchaseId (optional), unitPrice (per cigarette optional override), mood, context, notes; derived snapshotName,snapshotUnitPrice,cost,costType,snapshotTarMg,snapshotNicotineMg,snapshotCoMg (backend owns).
- sessions: startedAt, endedAt (optional), timePrecision, context, notes.
- drinks: sessionId, productId (optional), occurredAt, timePrecision, inputMode `BOTTLES|ML`, bottles, bottleMl, share (0..1), volumeMl, abv, purchaseId, unitPrice (per bottle optional), notes; derived actualMl,ethanolGrams,cost,costType,snapshotName,snapshotBottleMl,snapshotAbv,snapshotUnitPrice. Never double count volume inputs.
- intimacy: occurredAt,timePrecision,count(default1),durationMinutes,moodBefore,moodAfter,impact,manualAlcohol `AUTO|YES|NO|UNKNOWN`,overrideReason,notes.
- body: measuredAt,heightCm,weightKg,age,sex `MALE|FEMALE|UNSPECIFIED`,notes.
- completeness: date,kind `SMOKING|ALCOHOL|INTIMACY`,complete:boolean.
- goals: kind,period `DAY|WEEK|MONTH`,limitValue,unit,enabled.
- health: title,topic,body,sourceUrl,reviewedAt.

Settings defaults: theme dark, reducedMotion false, hidden false, reminderMode GENTLE, recentAlcoholHours 24, quietStart 23:00,quietEnd 08:00, dailyReminderLimit 3, defaultSmokingProductId null, defaultAlcoholProductId null.

Schema: `{name,label,fields:[{key,label,type:"text|number|datetime|date|boolean|select|textarea|product|session|purchase",required?,options?:[{value,label}]}]}`.

## Calculation service integration

Package `cn.ziji.domain`. Calculation agent owns `BehaviorCalculator.java` and tests. Spring independent public class with static methods:

- `Map<String,Object> enrich(String collection, Map<String,Object> input, Map<String,Object> previous, Map<String,List<Map<String,Object>>> state)` validates derived smoking/drink snapshots. `previous=null` for create. Preserve snapshots on unrelated edits; explicit product/amount/price changes recompute appropriately. Throw IllegalArgumentException for invalid data.
- `Map<String,Object> analytics(Map<String,List<Map<String,Object>>> state, Map<String,Object> settings, LocalDate from, LocalDate to, LocalDateTime now)` returns daily/totals/alcohol/links/warnings.

Backend calls enrich before writes and analytics on query. Root owns catalog adapter `cn.ziji.catalog` and startup/packaging scripts; frontend agent owns all `frontend/**`; backend agent owns backend except domain and catalog. Communicate interface deviations before changing.
