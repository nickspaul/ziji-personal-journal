import fs from 'node:fs/promises';
const root = new URL('../', import.meta.url);
const clean = s => s.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi,' ').replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi,' ').replace(/<[^>]+>/g,' ').replace(/&nbsp;|&#160;/g,' ').replace(/&amp;/g,'&').replace(/\s+/g,' ').trim();
const number = (s,re) => {const m=s.match(re);return m ? Number(m[1]) : null;};
const date='2026-10-21';
const urls=['rawf2v','b36bv2','5nlrpx','9t4tfh','37hj17','43wxm5','c20khg','cmrpyg'];
const result=[];
for (const id of urls) {
  const url=`https://www.yanjiuhome.com/xiangyan/${id}.html`;
  const res=await fetch(url,{headers:{'User-Agent':'ZijiLocal/0.2 (personal product reference)'},signal:AbortSignal.timeout(12000)});
  if(!res.ok) continue;
  const html=await res.text();const text=clean(html);
  const name=clean(html.match(/<title>(.*?)<\/title>/is)?.[1]||'').split('|')[0].trim();
  const brand=name.split(/[（(]/)[0];
  const p={kind:'SMOKING',brand,name,variant:name,unitCount:number(text,/单盒\(包\)支数\s*(\d+)/),tarMg:number(text,/焦油量\s*([\d.]+)\s*mg/i),nicotineMg:number(text,/烟气烟碱量\s*([\d.]+)\s*mg/i),coMg:number(text,/一氧化碳量\s*([\d.]+)\s*mg/i),constituentMethod:'第三方产品页转录的包装标示，具体版本待核对',sourceUrl:url,sourceDate:date,verified:false,favorite:false,locked:false,active:true,notes:'请核对自己的烟盒标示与包装版本。价格是第三方参考价，不是实际成交价。'};
  const price=number(text,/参考价格\s*[¥￥]\s*([\d.]+)/);
  result.push({id:`yanjiu-${id}`,product:p,price:price==null?null:{amount:price,priceType:'REFERENCE',effectiveDate:date,currency:'CNY',channel:'烟酒之家公开参考价',sourceUrl:url,verified:false},sourceLabel:'烟酒之家 · 第三方资料',sourceUrl:url,note:'包装标示与参考价，导入前请核对；不是人体实际吸收量。'});
}
const liquorUrl='https://shop.jiuxian.com/show/wap/act/viewShopActivity.htm?actId=7474&viewType=2';
const liquor=await fs.readFile(new URL('.local/catalog/jiuxian.html',root),'utf8');
for(const item of liquor.matchAll(/<li\b[^>]*proid="(\d+)"[^>]*>([\s\S]*?)<\/li>/gi)){
 const body=item[2];const name=clean(body.match(/<div\s+class="name"[^>]*>([\s\S]*?)<\/div>/i)?.[1]||'');
 if(!name||/整箱|\*\s*\d|×\s*\d|套装/.test(name))continue;
 const price=number(clean(body.match(/<div\s+class="price"[^>]*>([\s\S]*?)<\/div>/i)?.[1]||''),/[¥￥]\s*([\d.]+)/);
 const ml=number(name,/(\d+(?:\.\d+)?)\s*ml/i);const abv=number(name,/(\d+(?:\.\d+)?)\s*[°度]/);
 if(!ml||abv==null||price==null)continue;
 const brand=name.includes('贵州大曲')?'贵州大曲':name.includes('茅台王子')?'茅台王子':name.includes('茅台迎宾')?'茅台迎宾':'茅台';
 const p={kind:'ALCOHOL',brand,name,variant:`${abv}%vol · ${ml}ml`,bottleMl:ml,abv,sourceUrl:liquorUrl,sourceDate:date,verified:false,favorite:false,locked:false,active:true,notes:'酒仙公开零售页面展示价；具体版本、酒精度与本地成交价格请核对。'};
 result.push({id:`jiuxian-${item[1]}`,product:p,price:{amount:price,priceType:'REFERENCE',effectiveDate:date,currency:'CNY',channel:'酒仙公开页面展示价',sourceUrl:liquorUrl,verified:false},sourceLabel:'酒仙 · 页面展示价',sourceUrl:liquorUrl,note:'零售页面参考价可能变化；ABV和容量由具体商品名称提取，导入前请核对。'});
 if(result.length>=18)break;
}
await fs.mkdir(new URL('backend/src/main/resources/',root),{recursive:true});
await fs.writeFile(new URL('backend/src/main/resources/catalog-seed.json',root),JSON.stringify(result,null,2));
process.stdout.write(`Saved ${result.length} sourced catalog candidates.\n`);
