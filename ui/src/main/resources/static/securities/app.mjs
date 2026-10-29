/* 五证券终端：安全会话、主动模拟入金、HTTP 权威快照和固定幂等键跟踪异步请求。 */
import {cents,shares,dollars,decimal,total,Selection,websocketEndpoint} from './domain.mjs';
import {drawChart} from './chart.mjs';
import {mountDeposits} from './deposits.mjs';
let stopDeposits=()=>{};
/** 固定 ID 的局部查找器，不接收用户输入生成 HTML 或选择器。 */
const $=id=>document.getElementById(id);
let session,user,catalog=[],socket,selection=new Selection(),reading,refreshing=false,queued=false,activeBars=[],accountHealthy=false,pending=[],resolving=false,closed=false,connectTimer,refreshTimer,resolveTimer;
/** 文本写入不会执行账户名称、错误信息或服务端字段里的 HTML。 */
function text(id,value){$(id).textContent=value;}
/** 统一请求携带 CSRF，保留上游状态，不将 202 当成最终成功。 */
async function api(path,{method='GET',body,key,signal}={}){
  const headers={Accept:'application/json'};if(method!=='GET'){headers['Content-Type']='application/json';headers['X-CSRF-Token']=session.csrf;}if(key)headers['Idempotency-Key']=key;
  const response=await fetch(path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal,credentials:'same-origin',cache:'no-store'});
  let value={};try{value=await response.json();}catch(invalid){if(response.ok)throw new Error('响应格式错误');}
  if(!response.ok){const error=new Error(value.message||({401:'登录已过期，请重新登录',403:'会话校验失败，请刷新页面',409:'请求标识或邮箱冲突',503:'服务恢复或暂不可用，请稍后重试'}[response.status])||`请求失败 (${response.status})`);error.status=response.status;error.data=value;if(response.status===401)expire();throw error;}
  return {value,status:response.status};
}
/** 登录失效后停止使用旧私有数据，但不把未决委托说成失败。 */
function expire(){closed=true;stopDeposits();socket?.close();clearTimeout(connectTimer);clearInterval(refreshTimer);clearInterval(resolveTimer);$('terminal').hidden=true;$('auth').hidden=false;text('username','');text('connection','登录已过期');}
/** 表格完全用 DOM 文本构建，按钮函数不依赖可执行 HTML 字符串。 */
function table(id,headers,rows){const parent=$(id);parent.replaceChildren();if(!rows.length){parent.textContent='暂无记录';return;}const table=document.createElement('table'),head=document.createElement('tr');for(const title of headers){const cell=document.createElement('th');cell.textContent=title;head.append(cell);}table.append(head);for(const row of rows){const line=document.createElement('tr');for(const value of row){const cell=document.createElement('td');if(value instanceof Node)cell.append(value);else cell.textContent=value;line.append(cell);}table.append(line);}parent.append(table);}
/** 操作按钮显式绑定闭包，订单证券在创建时就固定，切换股票不能改写其目标。 */
function button(label,action){const node=document.createElement('button');node.type='button';node.textContent=label;node.addEventListener('click',()=>action().catch(showError));return node;}
/** 可见错误替代控制台持续打印，网络中断不清零资产。 */
function showError(error){if(error.name!=='AbortError')text('notice',error.message||'操作失败，请稍后重试');}
/** 仅保存可重试命令及键，不保存密码、Cookie、Bearer 或推送票据。 */
function persist(){sessionStorage.setItem('securities.pending.'+user.id,JSON.stringify(pending));renderPending();}
/** 展示未决状态，即使 HTTP 超时也不提示重复下单。 */
function renderPending(){table('pending',['证券','操作','状态'],pending.map(item=>[catalog.find(s=>s.securityId===String(item.securityId))?.symbol||item.securityId,item.label,item.requestId?'等待引擎确认':'提交待确认，使用原标识重试']));}
/** 提交前先登记固定幂等键，页面刷新后仍能继续查询同一笔请求。 */
async function submit(path,body,securityId,label){if(pending.length>=20)throw new Error('未决请求过多，请等待确认');const item={path,body,securityId:String(securityId),label,key:crypto.randomUUID(),requestId:null};pending.push(item);persist();await resolvePending();}
/** 每笔请求失败保留原键；COMPLETED 只提示已执行，成交状态仍以委托和成交明细为准。 */
async function resolvePending(){if(resolving||closed||!user)return;resolving=true;try{for(const item of [...pending]){
    try{
      if(item.requestId){let result;try{if(!/^[A-Za-z0-9._:-]{1,100}$/.test(item.requestId))throw new Error('请求标识格式错误');result=(await api('/api/v1/requests/'+item.requestId)).value;}catch(error){if(error.status!==404)throw error;}
        if(result?.status==='COMPLETED'){pending=pending.filter(value=>value!==item);persist();text('notice',result.accepted?`${item.label}已执行`:`${item.label}被拒绝：${result.reason||'业务条件不满足'}`);await refresh();continue;}
        if(result)continue;
      }
      const result=await api(item.path,{method:'POST',body:item.body,key:item.key});item.requestId=result.value.requestId;persist();
    }catch(error){if(error.data?.requestId){item.requestId=error.data.requestId;persist();}if(error.status===400&&!item.requestId){pending=pending.filter(value=>value!==item);persist();text('notice',item.label+'被入口拒绝：'+error.message);}else if([403,409].includes(error.status)){text('notice',`${item.label}未确认：${error.message}。保留原请求，请勿重复点击下单。`);}else showError(error);}
  }}finally{resolving=false;}}
/** 当前证券数据统一以 HTTP 替换；WS 只提示失效，因此不把重放或乱序通知追加成重复 Tick。 */
async function refresh(){if(!user||closed||!selection.id)return;if(refreshing){queued=true;return;}refreshing=true;queued=false;let failures=0;const ticket={id:selection.id,version:selection.version};reading=new AbortController();const signal=reading.signal;
  try{
    const selected=catalog.find(s=>s.securityId===ticket.id),base='/api/v1/securities/'+ticket.id;
    const tasks=[['book',base+'/orderbook'],['ticks',base+'/ticks'],['bars',base+'/bars/'+$('period').value],['orders','/api/v1/orders?securityId='+ticket.id],['history','/api/v1/history/orders?securityId='+ticket.id],['account','/api/v1/account']];
    await Promise.all(tasks.map(async([kind,path])=>{/* 分项故障保留正常部分；旧代次响应及主动取消不能污染新市场。 */
      try{const value=(await api(path,{signal})).value;if(!selection.current(ticket)||closed)return;
        if(kind==='book'){const book=value.data;text('last-price',book.lastPrice==null?'—':decimal(book.lastPrice));levels('asks',book.asks,'sell');levels('bids',book.bids,'buy');}
        if(kind==='ticks')table('ticks',['时间','价格 USD','股数'],value.data.map(t=>[new Date(Number(t.createdAt)).toLocaleTimeString(),decimal(t.price),decimal(t.quantity,0)]));
        if(kind==='bars'){activeBars=value.data;drawChart($('chart'),activeBars,selected.symbol);const last=activeBars.at(-1);text('chart-caption',last?`最新 OHLC  ${decimal(last.open)} / ${decimal(last.high)} / ${decimal(last.low)} / ${decimal(last.close)} · 成交量 ${decimal(last.quantity,0)} 股`:'暂无成交，不生成虚构 K线');}
        if(kind==='orders')renderOrders(value,false,ticket.id);if(kind==='history')renderOrders(value,true,ticket.id);
        if(kind==='account'){renderAccount(value,ticket.id);accountHealthy=true;text('account-status','已从交易引擎读取');}
      }catch(error){if(!selection.current(ticket)||error.name==='AbortError')return;failures++;if(kind==='account'){accountHealthy=false;text('account-status','账户暂不可用（不代表零余额）');text('cash','暂不可用');text('holding','暂不可用');}showError(error);}
    }));
  }finally{if(selection.current(ticket)&&!closed){text('market-status',failures?'部分数据暂不可用':'已同步 · '+new Date().toLocaleTimeString());if(!failures&&$('notice').textContent==='服务恢复或暂不可用，请稍后重试')text('notice','');}refreshing=false;if(queued&&!closed){queued=false;queueMicrotask(refresh);}}
}
/** 盘口是聚合价量，不显示对手账户与订单身份。 */
function levels(id,values,style){const parent=$(id);parent.replaceChildren();if(!values.length){parent.textContent='暂无委托';return;}for(const level of values.slice(0,8)){const row=document.createElement('div');row.className='row '+style;const price=document.createElement('span'),quantity=document.createElement('span');price.textContent=decimal(level.price);quantity.textContent=decimal(level.quantity,0)+' 股';row.append(price,quantity);parent.append(row);}}
/** 五持仓始终可见；总额使用十进制定点相加，不虚构成本或收益率。 */
function renderAccount(value,securityId){const cash=value.cash;text('cash',decimal(cash.available)+' / '+decimal(cash.frozen));const positions=new Map(value.positions.map(p=>[String(p.securityId),p]));const holding=positions.get(securityId);text('holding',holding?decimal(holding.available,0)+' / '+decimal(holding.frozen,0):'0 / 0');table('positions',['资产','可用','冻结','总额'],[['USD',decimal(cash.available),decimal(cash.frozen),total(cash.available,cash.frozen)],...catalog.map(s=>{const p=positions.get(s.securityId)||{available:'0',frozen:'0'};return [s.symbol+' '+s.name,decimal(p.available,0),decimal(p.frozen,0),total(p.available,p.frozen,0)];})]);}
/** 活动与历史字段名不同，撤单和明细均绑定读取时的证券及订单标识。 */
function renderOrders(values,history,securityId){table(history?'history':'orders',['订单','方向','价格','数量','剩余','状态','操作'],values.map(o=>[o.id,o.direction==='BUY'?'买入':'卖出',decimal(o.price),decimal(o.quantity,0),decimal(o.remaining??o.unfilledQuantity,0),o.status||'活动中',history?button('成交明细',async()=>{const result=await api(`/api/v1/orders/${o.id}/matches?securityId=${securityId}`);if(selection.id!==securityId)return;table('matches',['方向','价格','数量','时间'],result.value.map(m=>[m.direction,decimal(m.price),decimal(m.quantity,0),new Date(Number(m.createdAt)).toLocaleString()]));}):button('撤单',async()=>{if(pending.some(p=>p.path===`/api/v1/orders/${o.id}/cancel?securityId=${securityId}`))throw new Error('这张订单的撤单正在确认');await submit(`/api/v1/orders/${o.id}/cancel?securityId=${securityId}`,{},securityId,'撤单 '+o.id);})]));}
/** 切股立即取消旧请求、清空旧市场展示，然后更换服务端订阅。 */
async function select(id){selection.select(id);reading?.abort();activeBars=[];for(const area of ['asks','bids','ticks','orders','history'])text(area,'读取中…');text('matches','点击历史委托的“成交明细”查看。');text('last-price','—');text('holding','—');const selected=catalog.find(s=>s.securityId===String(id));text('market-title',selected.symbol+' · '+selected.name);for(const node of $('symbols').children)node.classList.toggle('selected',node.dataset.id===String(id));drawChart($('chart'),[],selected.symbol);subscribe();await refresh();}
/** 服务端订阅确认后再次读快照，覆盖握手与 HTTP 读取之间发生的成交；不合并通知载荷。 */
function subscribe(){if(socket?.readyState===WebSocket.OPEN){socket.send(JSON.stringify({action:'unsubscribe',securityIds:catalog.map(s=>s.securityId)}));socket.send(JSON.stringify({action:'subscribe',securityIds:[selection.id]}));}}
/** 票据仅保存在连接闭包；断线、订阅确认与 resync 都触发 HTTP 重建，5 秒轮询兜底。 */
async function connect(){if(closed)return;try{const {value}=await api('/api/v1/ws-ticket',{method:'POST',body:{}});if(closed)return;const endpoint=websocketEndpoint(session.websocket,location.href);endpoint.searchParams.set('ticket',value.ticket);const ws=new WebSocket(endpoint);socket=ws;
  ws.addEventListener('open',()=>{if(ws!==socket)return;text('connection','WebSocket 已连接');subscribe();});
  ws.addEventListener('message',event=>{if(ws!==socket||closed)return;try{const value=JSON.parse(event.data);if(value.type==='subscription.ack'){const expected=value.securityIds?.map(String)||[];if(expected.includes(selection.id))refresh();}else if(value.type==='resync.required'||value.type==='account.changed'||String(value.securityId)===selection.id)refresh();}catch(invalid){text('connection','推送数据异常，正在重新同步');refresh();}});
  ws.addEventListener('close',()=>{if(closed||ws!==socket)return;text('connection','WebSocket 重连中（HTTP 查询可用）');connectTimer=setTimeout(connect,3000);});
  ws.addEventListener('error',()=>{/* 浏览器随后触发 close；不打印带一次性票据的 URL。 */});
 }catch(error){if(!closed){text('connection','推送暂不可用，稍后重连');connectTimer=setTimeout(connect,5000);}}}
/** 初始化身份、主动入金入口与行情监听；普通注册和演示进度轮询不自动充值。 */
async function start(){
  session=(await api('/session')).value;user=session.user.id?session.user:null;
  const signup=location.pathname==='/signup';$('auth').hidden=!!user;$('terminal').hidden=!user;$('logout').hidden=!user;
  if(!user){text('auth-title',signup?'创建模拟交易账户':'登录你的交易账户');text('auth-submit',signup?'注册（不自动配资）':'登录');$('name-label').hidden=!signup;$('name').required=signup;text('auth-switch',signup?'已有账户，去登录':'创建账户');$('auth-switch').href=signup?'/signin':'/signup';}
  else{
    text('username',user.name);catalog=(await api('/api/v1/securities')).value.map(s=>({...s,securityId:String(s.securityId)}));
    try{const stored=JSON.parse(sessionStorage.getItem('securities.pending.'+user.id)||'[]');pending=Array.isArray(stored)?stored.filter(p=>typeof p.key==='string'&&typeof p.path==='string'&&p.path.startsWith('/api/v1/orders')).slice(0,20):[];}catch(invalid){pending=[];}
    for(const security of catalog){const node=button(security.symbol+' '+security.name,()=>select(security.securityId));node.dataset.id=security.securityId;$('symbols').append(node);}renderPending();await select(catalog[0].securityId);stopDeposits=mountDeposits({api,user,refresh,showError});connect();resolvePending();refreshTimer=setInterval(refresh,5000);resolveTimer=setInterval(resolvePending,2500);
  }
  showDemo(session.demo);
  if(['RUNNING','FAILED'].includes(session.demo.state)){const timer=setInterval(async()=>{try{const value=(await api('/session')).value;showDemo(value.demo);if(value.demo.state==='COMPLETE'){clearInterval(timer);refresh();}}catch(error){showError(error);}},3000);}
}
/** 明确初始化进行中与失败；不把已配置开关当成演示交易已完成。 */
function showDemo(value){text('demo',value.state==='DISABLED'?'':`演示初始化：${value.state} · ${value.completed}/${value.total} 步 · ${value.message}`);$('place-order').disabled=['RUNNING','FAILED'].includes(value.state);}
/** 预览只做定点运算，验证失败不提交半截数字。 */
function preview(){try{text('amount',dollars(cents($('price').value)*shares($('quantity').value)));}catch(invalid){text('amount','—');}}
$('price').addEventListener('input',preview);$('quantity').addEventListener('input',preview);
/** 表单绑定先捕获证券；提交按钮短期防重复，持久幂等键负责网络重试。 */
$('order-form').addEventListener('submit',async event=>{event.preventDefault();const node=$('place-order');if(node.disabled)return;try{if(!accountHealthy)throw new Error('账户尚未就绪，请刷新后重试');const securityId=selection.id,price=dollars(cents($('price').value)),quantity=shares($('quantity').value).toString(),direction=$('direction').value;node.disabled=true;await submit('/api/v1/orders',{securityId,direction,price,quantity},securityId,direction==='BUY'?'买入委托':'卖出委托');}catch(error){showError(error);}finally{node.disabled=false;}});
/** 身份写操作也验证匿名会话 CSRF，成功后整页重载以取得轮换后的令牌。 */
$('auth-form').addEventListener('submit',async event=>{event.preventDefault();$('auth-submit').disabled=true;try{await api('/auth/'+(location.pathname==='/signup'?'signup':'signin'),{method:'POST',body:{email:$('email').value,name:$('name').value,password:$('password').value}});location.assign('/');}catch(error){showError(error);}finally{$('auth-submit').disabled=false;}});
/** 注销清除浏览器私人暂存，但已经提交的订单仍由引擎执行。 */
$('logout').addEventListener('click',async()=>{try{await api('/auth/signout',{method:'POST',body:{}});closed=true;socket?.close();sessionStorage.removeItem('securities.pending.'+user.id);location.assign('/signin');}catch(error){showError(error);}});
$('refresh').addEventListener('click',()=>refresh().catch(showError));$('history-refresh').addEventListener('click',()=>refresh().catch(showError));
/** 周期切换也使用新代次，避免旧分钟线响应覆盖新的日线。 */
$('period').addEventListener('change',()=>select(selection.id).catch(showError));
/** 响应式只重绘现有权威 K线，不请求或补造数据。 */
window.addEventListener('resize',()=>drawChart($('chart'),activeBars,catalog.find(s=>s.securityId===selection.id)?.symbol||''));
start().catch(showError);
