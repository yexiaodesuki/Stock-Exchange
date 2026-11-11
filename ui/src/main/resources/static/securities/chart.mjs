/* 本地 Canvas 蜡烛图：只负责展示已存在的 OHLC，不生成成交、不参与金额计算。 */
/** 按后端升序 K线绘制最多 80 根蜡烛；Number 转换仅用于像素定位。 */
export function drawChart(canvas,bars,label){
  const ratio=globalThis.devicePixelRatio||1,width=Math.max(260,canvas.clientWidth),height=290;canvas.width=width*ratio;canvas.height=height*ratio;const ctx=canvas.getContext('2d');ctx.scale(ratio,ratio);ctx.clearRect(0,0,width,height);ctx.font='12px system-ui';ctx.fillStyle='#91a9b9';
  const values=bars.slice(-80);if(!values.length){ctx.fillText('暂无成交 · 等待第一笔真实模拟撮合',20,135);return;}
  const max=Math.max(...values.map(b=>Number(b.high))),min=Math.min(...values.map(b=>Number(b.low))),pad=Math.max((max-min)*.15,max*.002,.01),low=min-pad,high=max+pad;
  /** 价格坐标只影响图形，不返回交易字段。 */
  const y=price=>20+(high-Number(price))/(high-low)*(height-60),plotWidth=width-80,step=plotWidth/Math.max(values.length,8);
  for(let i=0;i<=4;i++){const price=low+(high-low)*i/4,top=y(price);ctx.strokeStyle='#273946';ctx.beginPath();ctx.moveTo(0,top);ctx.lineTo(plotWidth+8,top);ctx.stroke();ctx.fillText(price.toFixed(2),plotWidth+12,top+4);}
  values.forEach((bar,index)=>{/* 每根蜡烛来自一条持久 K线，开收相等仍画出横线。 */
    const x=step*(index+.5),open=y(bar.open),close=y(bar.close);ctx.strokeStyle=Number(bar.close)>=Number(bar.open)?'#46dbc0':'#ff8e9d';ctx.fillStyle=ctx.strokeStyle;ctx.beginPath();ctx.moveTo(x,y(bar.high));ctx.lineTo(x,y(bar.low));ctx.stroke();ctx.fillRect(x-Math.min(16,step*.55)/2,Math.min(open,close),Math.min(16,step*.55),Math.max(1,Math.abs(open-close)));
  });
  ctx.fillStyle='#91a9b9';ctx.fillText(label+' · '+new Date(Number(values[0].startTime)).toLocaleTimeString(),8,height-8);
}
