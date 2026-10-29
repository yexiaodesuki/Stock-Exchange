/* 页面纯逻辑回归：精确金额、服务端精度与证券切换代次，不需要运行任何业务服务。 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
/** 登录页不公开演示账号提示，执行通知不额外附加成交免责声明，也不冒称全部成交。 */
test('页面文案保持简洁且不改变执行结果含义',()=>{const html=readFileSync(new URL('../../main/resources/static/securities/index.html',import.meta.url),'utf8');const app=readFileSync(new URL('../../main/resources/static/securities/app.mjs',import.meta.url),'utf8');assert.ok(!html.includes('演示账户：'));assert.ok(!html.includes('密码见本地'));assert.ok(!html.includes('.env.securities'));assert.ok(!app.includes('不代表全部成交'));assert.ok(app.includes('`${item.label}已执行`'));assert.ok(app.includes('`${item.label}被拒绝：'));});
import {cents,shares,dollars,decimal,total,Selection,websocketEndpoint} from '../../main/resources/static/securities/domain.mjs';
/** 网关必须跟随浏览器协议和端口，不把 HTTPS 页面降级到 ws 或绕过网关。 */
test('网关推送地址同源且支持 HTTPS',()=>{assert.equal(websocketEndpoint('/ws','http://localhost:8080/signin?x=1').href,'ws://localhost:8080/ws');assert.equal(websocketEndpoint('/ws','https://exchange.example/signin').href,'wss://exchange.example/ws');assert.equal(websocketEndpoint('ws://127.0.0.1:8106/ws','http://localhost:8100/').href,'ws://127.0.0.1:8106/ws');for(const value of ['//evil/ws','/other','http://evil/ws','ws://evil/ws?ticket=x'])assert.throws(()=>websocketEndpoint(value,'http://localhost:8080/'));});
/** 小额与超出 Number 安全整数的金额也必须得到精确结果。 */
test('十进制价格乘整股不发生浮点误差',()=>{assert.equal(dollars(cents('0.10')*shares('3')),'0.30');assert.equal(dollars(cents('9999999999.99')*shares('1000000000')),'9999999999990000000.00');assert.equal(total('92062.500000000000000000','3100'),'95162.50');});
/** 无效输入不准被 parseFloat、parseInt 或隐式舍入接受。 */
test('拒绝指数、零、负数、分股与超额精度',()=>{for(const value of ['1e3','0','-1','1.001','Infinity','1x','10000000000000000'])assert.throws(()=>cents(value));for(const value of ['1.5','2x','0','1000000001'])assert.throws(()=>shares(value));assert.throws(()=>decimal('1.001'));assert.throws(()=>decimal('1.5',0));});
/** 后端 DECIMAL 固定尾零只影响展示，不改变实际资产。 */
test('展示与五证券持仓汇总保持整数精度',()=>{assert.equal(decimal('1015.000000000000000000',0),'1015');assert.equal(total('975','10',0),'985');assert.equal(decimal('0'),'0.00');});
/** STAR→GREEN 后即使旧请求最后返回，也不能覆盖 GREEN。 */
test('旧证券与旧周期响应被代次拒绝',()=>{const selected=new Selection();const star=selected.select('1001'),green=selected.select('1002');assert.equal(selected.current(star),false);assert.equal(selected.current(green),true);const daily=selected.select('1002');assert.equal(selected.current(green),false);assert.equal(selected.current(daily),true);});
