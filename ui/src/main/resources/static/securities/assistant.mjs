/* 自然语言只读助手：展示本人事实、模拟行情和白名单知识出处；只用文本节点，不执行模型内容。 */
export function mountAssistant({api,showError}){
  const $=id=>document.getElementById(id);let busy=false,stopped=false;
  /** RAG模式与分类结果分开标识，原文回退不能冒充模型生成。 */
  function knowledgeLabel(value){const labels={RAG_GENERATED:'基于项目文档生成 · 请核对引用',RAG_EXTRACTIVE:'语义检索原文 · 生成未启用',RAG_EXTRACTIVE_FALLBACK:'生成暂不可用 · 已回退检索原文',LEXICAL_FALLBACK:'原文检索 · 向量检索未启用或暂不可用'};return labels[value.mode]||modeLabel(value);}
  /** 后端只输出脱敏错误码，页面不展示供应商原文或把所有错误说成未配置。 */
  function modeLabel(value){if(value.mode==='MODEL_CLASSIFICATION')return '模型识别 · 业务数据解释';if(value.mode==='SAFETY_REFUSAL')return '只读安全限制';if(value.mode==='CLARIFICATION')return '需要你补充说明';if(value.mode==='UNSUPPORTED')return '暂不支持此问题';const reasons={MODEL_NOT_CONFIGURED:'模型未启用',MODEL_TIMEOUT:'模型超时',MODEL_ACCESS_DENIED:'模型访问被拒绝',MODEL_ENDPOINT_OR_NAME_NOT_FOUND:'模型接口或名称不存在',MODEL_RATE_LIMITED:'模型限流',MODEL_HTTP_ERROR:'模型接口异常',MODEL_TRUNCATED:'模型输出被截断',MODEL_EMPTY_CONTENT:'模型未返回答案',MODEL_INVALID_CLASSIFICATION:'模型分类无效',MODEL_INVALID_RESPONSE:'模型响应格式异常',MODEL_NETWORK_OR_RESPONSE_ERROR:'模型网络或响应异常',MODEL_INTERRUPTED:'模型请求中断'};return '规则说明 · '+(reasons[value.fallbackReason]||'本地识别');}
  /** 所有文字用 textContent，财务数字保留字符串；时间列才转安全范围内的毫秒时间。 */
  function render(value){const host=$('assistant-facts');host.replaceChildren();for(const summary of value.highlights||[]){const node=document.createElement('p');node.className='assistant-highlight';node.textContent=summary;host.append(node);}for(const card of value.tables||[]){const section=document.createElement('section'),title=document.createElement('h3'),scroll=document.createElement('div');title.textContent=card.title;scroll.className='assistant-table';section.append(title,scroll);if(!card.rows.length)scroll.textContent='暂无记录';else{const table=document.createElement('table'),head=document.createElement('tr');for(const name of card.columns){const cell=document.createElement('th');cell.textContent=name;head.append(cell);}table.append(head);for(const cells of card.rows){const row=document.createElement('tr');cells.forEach((text,index)=>{const cell=document.createElement('td');cell.textContent=card.columns[index]?.includes('时间')&&/^\d{10,14}$/.test(text)?new Date(Number(text)).toLocaleString():text;row.append(cell);});table.append(row);}scroll.append(table);}host.append(section);}const source=document.createElement('p');source.className='hint';source.textContent='数据时间：'+new Date(Number(value.asOf)).toLocaleString()+(value.facts?.sequenceId?'；引擎事件位置：'+value.facts.sequenceId:'')+(value.facts?.source?'；来源：'+value.facts.source:'');host.append(source);}
  /** 来源由后端白名单资源生成；不将模型 URL 或 HTML 变成页面链接或可执行内容。 */
  function renderReferences(value){
    const host=$('assistant-facts');
    if(value.facts?.projectionSequence){const progress=document.createElement('p');progress.className='hint';progress.textContent='行情投影位置：'+value.facts.projectionSequence;host.append(progress);}
    for(const source of value.sources||[]){const details=document.createElement('details'),title=document.createElement('summary'),excerpt=document.createElement('p');details.className='assistant-source';title.textContent=(source.id?'['+source.id+'] ':'')+'来源：'+source.title+' · '+source.section+'（版本 '+source.version+'）';excerpt.textContent=source.excerpt;details.append(title,excerpt);host.append(details);}
  }
  /** 只提交自然语言，行情和知识也沿用已登录 Session/CSRF，不新增资金操作。 */
  async function submit(event){event.preventDefault();if(busy||stopped)return;busy=true;$('assistant-submit').disabled=true;$('assistant-facts').replaceChildren();$('assistant-message').textContent='正在查询业务数据或项目文档…';try{const {value}=await api('/api/v1/assistant/explain',{method:'POST',body:{question:$('assistant-question').value}});if(stopped)return;$('assistant-message').textContent=knowledgeLabel(value)+'：'+value.explanation;render(value);renderReferences(value);}catch(error){if(!stopped){$('assistant-message').textContent='无法完成查询，请稍后重试或使用原账户、订单、入金及行情页面；没有执行任何资金操作。';showError(error);}}finally{busy=false;$('assistant-submit').disabled=false;}}
  $('assistant-form').addEventListener('submit',submit);
  const shortcuts=[...document.querySelectorAll('[data-assistant-question]')];
  /** 快捷问题与键盘输入走同一链路，不含隐藏用户或证券编号。 */
  function quick(event){$('assistant-question').value=event.currentTarget.dataset.assistantQuestion;$('assistant-form').requestSubmit();}
  shortcuts.forEach(node=>node.addEventListener('click',quick));
  // 登录失效后不再展示晚到的私人响应，也不保留对话内容。
  return ()=>{stopped=true;$('assistant-form').removeEventListener('submit',submit);shortcuts.forEach(node=>node.removeEventListener('click',quick));$('assistant-facts').replaceChildren();$('assistant-message').textContent='';};
}
