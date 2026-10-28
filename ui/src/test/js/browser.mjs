/* 真实 Chromium 页面验收：隔离买卖 Cookie、五证券切换、挂单成交撤单及未决请求刷新恢复。 */
import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import path from 'node:path';
const base=process.env.SECURITIES_BROWSER_URL;
if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(base||''))throw new Error('浏览器测试只允许随机本机测试端口');
const options={headless:true};if(process.env.SECURITIES_BROWSER_EXECUTABLE)options.executablePath=process.env.SECURITIES_BROWSER_EXECUTABLE;else if(process.platform==='win32')options.channel='msedge';
const browser=await chromium.launch(options),buyer=await browser.newContext({viewport:{width:1440,height:1080}}),seller=await browser.newContext({viewport:{width:1440,height:1080}}),errors=[],recovering=[],pendingLookups=[],offlineResponses=[];
let offlineWindow=false;
/** 有界等待 DOM 或资金状态，不能用固定长休眠宣称联调通过。 */
async function until(check,label){const end=Date.now()+20000;while(Date.now()<end){if(await check())return;await new Promise(resolve=>setTimeout(resolve,100));}throw new Error('浏览器等待超时：'+label);}
/** 真正填写页面表单，登录身份不通过伪造页面状态注入。 */
async function login(context,email,password){const page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));page.on('console',message=>{if(message.type()==='error'){const text=message.text().replace(/ticket=[^\s]+/g,'ticket=[redacted]');if(/status of 503/.test(text))recovering.push(Date.now());else if(/status of 404/.test(text)&&message.location().url.includes('/api/v1/requests/'))pendingLookups.push(Date.now());else if(offlineWindow&&text.includes('ERR_INTERNET_DISCONNECTED'))offlineResponses.push(Date.now());else errors.push(text);}});await page.goto(base+'/signin');try{await page.locator('#auth').waitFor({state:'visible',timeout:8000});}catch(failure){throw new Error('登录页面加载失败：'+await page.locator('#notice').innerText()+'；'+errors.join(';'));}await page.locator('#email').fill(email);await page.locator('#password').fill(password);await page.getByRole('button',{name:'登录',exact:true}).click();await page.locator('#terminal').waitFor({state:'visible'});await until(async()=>!(await page.locator('#cash').innerText()).includes('—'),'账户加载');try{await until(async()=>(await page.locator('#connection').innerText()).includes('已连接'),'WebSocket 连接');}catch(failure){throw new Error('推送连接失败：'+await page.locator('#connection').innerText()+'；'+errors.join(';'));}return page;}
/** 通过已登录页面只读查询权威账户，用十进制结果断言 UI 交易后的资产。 */
async function account(page){return page.evaluate(async()=>{const response=await fetch('/api/v1/account');if(!response.ok)throw new Error('账户读取不可用');return response.json();});}
/** 页面按钮切换到指定证券，等待盘口与当前订单属于本次代次。 */
async function select(page,symbol,name){await page.getByRole('button',{name:symbol+' '+name,exact:true}).click();await until(async()=>(await page.locator('#market-title').innerText()).startsWith(symbol)&&!(await page.locator('#orders').innerText()).includes('读取中'),'切换 '+symbol);}
/** 页面真实限价下单；等待请求最终确认，而非只等待 HTTP 202。 */
async function place(page,direction,price,quantity){await page.locator('#direction').selectOption(direction);await page.locator('#price').fill(price);await page.locator('#quantity').fill(quantity);await page.getByRole('button',{name:'提交委托',exact:true}).click();await until(async()=>(await page.locator('#pending').innerText()).includes('暂无记录'),'引擎确认');}
/** 等待微小网络延时用 Promise，断言仍以可观察 DOM 为依据。 */
function pause(ms){return new Promise(resolve=>setTimeout(resolve,ms));}
try{
  const a=await login(buyer,'user1@example.com',process.env.SECURITIES_BROWSER_BUYER_PASSWORD),b=await login(seller,'user2@example.com',process.env.SECURITIES_BROWSER_SELLER_PASSWORD);
  assert.equal(await a.locator('#username').innerText(),'用户一');assert.equal(await b.locator('#username').innerText(),'用户二');
  for(const [symbol,name] of [['STAR','星河科技'],['GREEN','青山能源'],['CARE','安康医药'],['DAILY','优选消费'],['SAIL','远航工业']]){await select(a,symbol,name);await until(async()=>(await a.locator('#ticks tr').count())===4,'每证券三条演示成交');assert.match(await a.locator('#chart-caption').innerText(),/最新 OHLC/);}
  // 触发多个交错请求，最后的 SAIL 页面不能被 STAR/GREEN 的慢响应覆盖。
  await a.route('**/securities/1001/ticks',async route=>{await pause(700);try{await route.continue();}catch(aborted){/* 快速切股会取消旧请求；已取消路由不能继续发送。 */}});
  await a.getByRole('button',{name:'STAR 星河科技',exact:true}).click();await a.getByRole('button',{name:'GREEN 青山能源',exact:true}).click();await a.getByRole('button',{name:'SAIL 远航工业',exact:true}).click();await until(async()=>(await a.locator('#market-title').innerText()).startsWith('SAIL')&&!(await a.locator('#orders').innerText()).includes('读取中'),'最后切股生效');await a.unroute('**/securities/1001/ticks');
  await select(a,'CARE','安康医药');await select(b,'CARE','安康医药');
  for(const page of [a,b]){await page.locator('#orders').getByRole('button',{name:'撤单',exact:true}).click();await until(async()=>(await page.locator('#orders').innerText()).includes('暂无记录'),'撤销演示挂单并解冻');}
  const beforeBuyer=await account(a),beforeSeller=await account(b);
  await place(b,'SELL','85.00','2');await until(async()=>(await b.locator('#orders tr').count())===2,'卖方挂单');await place(a,'BUY','86.00','2');
  await until(async()=>(await a.locator('#ticks tr').count())===5,'CARE 新成交');
  const afterBuyer=await account(a),afterSeller=await account(b);
  /** 后端余额转整数分只用于测试断言，拒绝舍入真实精度。 */
  const money=value=>BigInt(String(value).split('.')[0])*100n+BigInt((String(value).split('.')[1]||'').padEnd(2,'0').slice(0,2));
  assert.equal(money(beforeBuyer.cash.available)-money(afterBuyer.cash.available),17000n);assert.equal(money(afterSeller.cash.available)-money(beforeSeller.cash.available),17000n);
  assert.match(await a.locator('#ticks').innerText(),/85.00/);
  // 下单已提交但 UI 尚未拿到响应时刷新：sessionStorage 原键重试不能生成两张订单。
  let sent=false;await a.route('**/api/v1/orders',async route=>{if(route.request().method()!=='POST'){await route.continue();return;}const response=await route.fetch();sent=true;await pause(1500);try{await route.fulfill({response});}catch(aborted){/* 刷新主动取消旧页面的响应。 */}});
  await a.locator('#price').fill('1.00');await a.locator('#quantity').fill('1');await a.getByRole('button',{name:'提交委托',exact:true}).click();await until(async()=>sent,'订单已提交');await a.reload();await a.unroute('**/api/v1/orders');await a.locator('#terminal').waitFor({state:'visible'});await select(a,'CARE','安康医药');await until(async()=>(await a.locator('#pending').innerText()).includes('暂无记录')&&(await a.locator('#orders tr').count())===2,'刷新保留原请求并只生成一单');
  await a.locator('#orders').getByRole('button',{name:'撤单',exact:true}).click();await until(async()=>(await a.locator('#orders').innerText()).includes('暂无记录'),'撤单恢复可用资金');
  offlineWindow=true;await buyer.setOffline(true);await pause(1000);await buyer.setOffline(false);await a.reload();await until(async()=>(await a.locator('#connection').innerText()).includes('已连接'),'恢复网络重新连接');await select(a,'CARE','安康医药');await until(async()=>(await a.locator('#ticks tr').count())===5,'重连恢复权威行情');offlineWindow=false;
  await until(async()=>(await a.locator('#market-status').innerText()).includes('已同步')&&(await b.locator('#market-status').innerText()).includes('已同步'),'行情与历史最终恢复正常');
  assert.doesNotMatch(await a.locator('#history').innerText(),/读取中|数值格式错误/);assert.doesNotMatch(await a.locator('#notice').innerText(),/错误|暂不可用/);
  await a.locator('#history tr').filter({hasText:'FULLY_FILLED'}).first().getByRole('button',{name:'成交明细',exact:true}).click();
  await until(async()=>(await a.locator('#matches tr').count())===2,'本人历史成交明细');assert.match(await a.locator('#matches').innerText(),/85.00/);
  const settled=recovering.length;await pause(5500);assert.equal(recovering.length,settled,'恢复后一个完整轮询周期内不能持续出现 503');
  await a.screenshot({path:path.join(process.env.SECURITIES_BROWSER_OUTPUT,'securities-ui.png'),fullPage:true});
  await a.setViewportSize({width:390,height:844});await pause(200);assert.ok(await a.locator('#cash').isVisible());await a.screenshot({path:path.join(process.env.SECURITIES_BROWSER_OUTPUT,'securities-ui-mobile.png'),fullPage:true});
  const resources=await a.evaluate(()=>performance.getEntriesByType('resource').filter(e=>!e.name.startsWith(location.origin)&&!e.name.startsWith('data:')).map(e=>e.name));assert.deepEqual(resources,[]);
  assert.deepEqual(errors,[]);console.log('浏览器验收通过：双身份、五证券 K线、切股隔离、Maker 价格清算、撤单、刷新幂等、重连、桌面与移动布局。短暂追赶响应：'+recovering.length+'，未决查询 404：'+pendingLookups.length+'，主动断网响应：'+offlineResponses.length+'；最终稳定周期无持续错误。');
}finally{await buyer.close();await seller.close();await browser.close();}
