/* 浏览器纯逻辑：用整数分与整股计算金额，按用户保存未决请求，股票切换使用代次隔离。 */
/** 价格最多两位小数，禁止指数、负数、隐式舍入与超大值。 */
export function cents(value){if(typeof value!=='string'||!/^\d{1,10}(\.\d{1,2})?$/.test(value))throw new Error('价格必须为正数，最多两位小数');const [whole,fraction='']=value.split('.');const result=BigInt(whole)*100n+BigInt(fraction.padEnd(2,'0'));if(result<=0n||result>999999999999n)throw new Error('价格超出证券规则范围');return result;}
/** 整股输入不能用 parseInt 吞掉小数或无效后缀。 */
export function shares(value){if(typeof value!=='string'||!/^\d{1,10}$/.test(value)||BigInt(value)<=0n||BigInt(value)>1000000000n)throw new Error('数量必须为 1 至 1000000000 的整数股');return BigInt(value);}
/** 整数分转十进制文本；用于下单及预览，不经过二进制浮点。 */
export function dollars(value){return `${value/100n}.${(value%100n).toString().padStart(2,'0')}`;}
/** 后端定点字符串展示，允许任意无意义尾零，禁止把余额作为 Number 记账。 */
export function decimal(value,scale=2){const match=/^(\d+)(?:\.(\d+))?$/.exec(String(value));if(!match)throw new Error('服务端数值格式错误');const fraction=match[2]||'';if(/[1-9]/.test(fraction.slice(scale)))throw new Error('服务端数值精度错误');return match[1]+(scale?'.'+fraction.slice(0,scale).padEnd(scale,'0'):'');}
/** 汇总同一余额的可用与冻结，仅操作整数分或整数股。 */
export function total(available,frozen,scale=2){const a=decimal(available,scale).replace('.',''),b=decimal(frozen,scale).replace('.','');const value=BigInt(a)+BigInt(b);return scale?dollars(value):value.toString();}
/** 固定网关路径跟随当前站点选择 ws/wss；仍兼容测试和独立部署的显式绝对地址。 */
export function websocketEndpoint(endpoint,page){const base=new URL(page);if(endpoint==='/ws'){base.pathname='/ws';base.search='';base.hash='';base.protocol=base.protocol==='https:'?'wss:':'ws:';return base;}const url=new URL(endpoint);if(!['ws:','wss:'].includes(url.protocol)||url.username||url.password||url.search||url.hash)throw new Error('推送地址无效');return url;}
export class Selection {
  /** 页面代次只在切股时递增，HTTP 旧响应无法写入新股票。 */
  constructor(){this.id=null;this.version=0;}
  /** 返回不可变读取票据，配合 AbortController 避免浪费旧请求。 */
  select(id){this.id=String(id);return {id:this.id,version:++this.version};}
  /** 检查数据仍属于当前读取代次。 */
  current(ticket){return ticket.id===this.id&&ticket.version===this.version;}
}
