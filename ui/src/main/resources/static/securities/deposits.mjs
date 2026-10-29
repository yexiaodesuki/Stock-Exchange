/* 模拟入金交互：固定幂等申请、刷新续查和本人记录；超时不清除未决请求或虚增余额。 */
import {cents,dollars,decimal} from './domain.mjs';

/** 注入现有认证请求和权威刷新函数；返回清理方法，登录失效后停止私有轮询。 */
export function mountDeposits({api,user,refresh,showError}){
  const $=id=>document.getElementById(id),storage='securities.deposit.'+user.id;
  let pending=null,busy=false,stopped=false,page=0,policy=null;
  try{const value=JSON.parse(sessionStorage.getItem(storage)||'null');if(value&&typeof value.key==='string'&&/^[0-9]+\.[0-9]{2}$/.test(value.amount))pending=value;}catch(invalid){/* 无效暂存不伪造服务端申请，历史记录仍可查询。 */}
  /** 暂存不含密码、令牌或余额；只有原金额、幂等键和服务端记录编号。 */
  function persist(){if(pending)sessionStorage.setItem(storage,JSON.stringify(pending));else sessionStorage.removeItem(storage);}
  /** 文本渲染避免服务端字段或拒绝理由被当作 HTML 执行。 */
  function status(message){$('deposit-status').textContent=message;}
  /** 每页记录都来自本人 API，不在页面追加假流水或推算余额。 */
  async function history(){const {value}=await api('/api/v1/deposits?page='+page+'&limit=20');if(stopped)return;const host=$('deposit-history');host.replaceChildren();$('deposit-prev').disabled=page===0;$('deposit-next').disabled=value.length<20;if(!value.length){host.textContent='暂无记录';return;}const table=document.createElement('table');for(const cells of [['时间','金额 USD','状态','编号'],...value.map(d=>[new Date(Number(d.createdAt)).toLocaleString(),decimal(d.amount),{PENDING:'待确认',SUCCEEDED:'已到账',REJECTED:'已拒绝'}[d.status]||d.status,d.depositId])]){const row=document.createElement('tr');for(const text of cells){const cell=document.createElement('td');cell.textContent=text;row.append(cell);}table.append(row);}host.append(table);}
  /** 原申请已受理后只查状态；提交响应丢失时仍用原金额和键重试。 */
  async function resolve(){if(busy||stopped)return;busy=true;try{if(pending){const {value}=pending.depositId?await api('/api/v1/deposits/'+pending.depositId):await api('/api/v1/deposits',{method:'POST',body:{amount:pending.amount},key:pending.key});if(stopped)return;pending.depositId=value.depositId;persist();if(value.status==='PENDING')status('申请已受理，等待到账；请勿重复充值。');else{const accepted=value.status==='SUCCEEDED';pending=null;persist();status(accepted?'模拟资金已到账':'入金被拒绝：'+(value.reason||'业务条件不满足'));if(accepted)await refresh();}}if($('deposit-dialog').open)await history();}catch(error){if(stopped)return;if(pending&&!pending.depositId&&[400,429].includes(error.status)){pending=null;persist();}status(pending?'暂未确认，将使用原申请继续查询或重试。':error.message);showError(error);}finally{busy=false;$('deposit-submit').disabled=!!pending||!policy?.enabled;}}
  /** 打开时读取真实配置，关闭模拟开关不阻止查看旧记录和未决结果。 */
  async function open(){try{$('deposit-dialog').showModal();const {value}=await api('/api/v1/deposits/policy');policy=value;$('deposit-policy').textContent=`${value.enabled?'模拟入金已开启':'模拟入金已关闭'}；单笔 ${decimal(value.minimum)}～${decimal(value.maximum)} USD，每日 ${decimal(value.daily)} USD（UTC，含待确认申请）。`;$('deposit-submit').disabled=!!pending||!value.enabled;await resolve();}catch(error){showError(error);}}
  /** 持久保存原幂等键后才发请求，双击和刷新都不能生成第二个客户端申请。 */
  async function submit(event){event.preventDefault();if(pending||busy||!policy?.enabled)return;try{const amount=dollars(cents($('deposit-amount').value));const draft={key:crypto.randomUUID(),amount,depositId:null};sessionStorage.setItem(storage,JSON.stringify(draft));pending=draft;$('deposit-submit').disabled=true;await resolve();}catch(error){status(error.message||'无法保存申请，请检查浏览器存储权限');showError(error);}}
  $('deposit-open').addEventListener('click',open);$('deposit-close').addEventListener('click',()=>$('deposit-dialog').close());$('deposit-form').addEventListener('submit',submit);
  $('deposit-retry').addEventListener('click',()=>resolve());
  $('deposit-prev').addEventListener('click',()=>{if(page>0)page--;history().catch(showError);});$('deposit-next').addEventListener('click',()=>{page++;history().catch(showError);});
  document.querySelectorAll('[data-deposit-amount]').forEach(node=>node.addEventListener('click',()=>{$('deposit-amount').value=node.dataset.depositAmount;}));
  const timer=setInterval(()=>{if(pending||$('deposit-dialog').open)resolve();},3000);
  if(pending){status('恢复上次未确认的入金申请…');resolve();}
  // 清理不会删除服务端申请；重新登录后可继续从历史中查询。
  return ()=>{stopped=true;clearInterval(timer);$('deposit-dialog').close();};
}
