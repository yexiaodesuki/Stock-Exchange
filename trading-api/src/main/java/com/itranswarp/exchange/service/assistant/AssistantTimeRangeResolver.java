/* 行情时间窗口：只支持明确的滚动范围，统一 UTC 毫秒与左闭右开边界，不猜交易日。 */
package com.itranswarp.exchange.service.assistant;

import java.time.Clock;
import java.util.regex.Pattern;

public final class AssistantTimeRangeResolver {
  public record Range(long from,long to,String label){}
  private final Clock clock;
  private final int defaultHours,maxDays;
  /** 配置只允许已有的有界窗口，非法值阻止启动。 */
  public AssistantTimeRangeResolver(Clock clock,int defaultHours,int maxDays){
    if(defaultHours<1||defaultHours>24||maxDays<1||maxDays>7)throw new IllegalArgumentException("行情时间配置无效");
    this.clock=clock;this.defaultHours=defaultHours;this.maxDays=maxDays;
  }
  /** 不支持日历日期、多个范围或任意数字时要求澄清，默认窗口也必须在答案中公开。 */
  public Range resolve(String question){
    String normalized=question.replaceAll("(?<![一二三四五六七八九十百0-9])二十四小时","24小时").replaceAll("(?<![一二三四五六七八九十百0-9])一小时","1小时").replaceAll("(?<![一二三四五六七八九十百0-9])七天","7天");
    if(normalized.matches("(?s).*(今天|昨天|本周|上周|本月|上月|今年|去年|本年|上年|[0-9]{4}[-/年]|[0-9]+月|[0-9]+日|分钟|明天|后天).*"))throw new IllegalArgumentException("行情只支持最近1小时、24小时或7天，不按交易日统计。");
    if(normalized.matches("(?s).*[一二三四五六七八九十百]+(小时|天|周).*"))throw new IllegalArgumentException("请使用最近1小时、24小时或7天。");
    var match=Pattern.compile("([0-9]+)\\s*(小时|天)").matcher(normalized);long duration=0;String label="";int count=0;
    while(match.find()){
      count++;String amount=match.group(1),unit=match.group(2);
      if(unit.equals("小时")&&(amount.equals("1")||amount.equals("24"))){duration=Long.parseLong(amount)*3600000L;label="最近"+amount+"小时";}
      else if(unit.equals("天")&&amount.equals("7")){duration=7*86400000L;label="最近7天";}
      else throw new IllegalArgumentException("行情只支持最近1小时、24小时或7天。");
    }
    if(count>1)throw new IllegalArgumentException("请一次选择一个行情时间范围。");
    if(count==0){if(normalized.matches("(?s).*([一二三四五六七八九十百]+(小时|天|周)|历史|全部时间|一周|周内|全天).*"))throw new IllegalArgumentException("请使用最近1小时、24小时或7天。");duration=defaultHours*3600000L;label="最近"+defaultHours+"小时（默认）";}
    if(duration>maxDays*86400000L)throw new IllegalArgumentException("所选范围超出当前配置上限。");
    long to=clock.millis();return new Range(Math.max(0,to-duration),to,label);
  }
}
