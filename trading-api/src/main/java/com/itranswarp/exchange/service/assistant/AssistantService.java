/* 助手编排：身份与限流不变；本人数据、公开行情和知识各自分流，模型不能触发交易。 */
package com.itranswarp.exchange.service.assistant;

import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class AssistantService {
  private final AssistantReadService reads;
  private final AiModelClient model;
  private final AssistantSecurityResolver securities;
  private final AssistantPresentation presentation;
  private final AssistantMarketService market;
  private final AssistantKnowledgeService knowledge;
  private final AssistantTimeRangeResolver timeRanges;
  private final Semaphore slots=new Semaphore(2);
  private final Set<Long> active=ConcurrentHashMap.newKeySet();
  public record Question(String question,Long securityId,Long orderId,String depositId){}
  /** 只注入读取和分类能力，不引用下单、撤单或充值提交服务。 */
  public AssistantService(AssistantReadService reads,AiModelClient model,AssistantSecurityResolver securities){this(reads,model,securities,null,null,new AssistantTimeRangeResolver(java.time.Clock.systemUTC(),24,7));}
  /** 分支只注入各自的只读服务；兼容旧单元测试构造，但生产必须完整装配。 */
  public AssistantService(AssistantReadService reads,AiModelClient model,AssistantSecurityResolver securities,AssistantMarketService market,AssistantKnowledgeService knowledge,AssistantTimeRangeResolver timeRanges){this.reads=reads;this.model=model;this.securities=securities;this.presentation=new AssistantPresentation(securities);this.market=market;this.knowledge=knowledge;this.timeRanges=timeRanges;}
  /** 状态不泄露服务地址、模型密钥或账户上下文。 */
  public Object status(){return Map.of("modelConfigured",model.configured(),"mode",model.configured()?"MODEL_CLASSIFICATION":"RULES","readOnly",true,"knowledge",knowledge==null?Map.of("state","DISABLED"):knowledge.status());}
  /** 同一用户最多一请求，全局最多两请求；退出路径必须释放名额。 */
  public Object explain(long user,Question question){
    if(question==null||question.question()==null||question.question().isBlank()||question.question().length()>500)throw new IllegalArgumentException("问题需为1至500字符");
    if(!slots.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"助手繁忙，交易功能不受此限流影响");
    boolean entered=false;
    try {
      entered=active.add(user);if(!entered)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"请等待上一个助手请求");
      if(AssistantRules.forbidden(question.question()))return response("SAFETY_REFUSAL","READ_ONLY_OR_OWN_ACCOUNT_ONLY",AssistantRules.Intent.UNSUPPORTED,Map.of(),new AssistantPresentation.Result(AssistantRules.explain(AssistantRules.Intent.UNSUPPORTED),List.of("助手只查询你自己的数据，不执行充值、下单或撤单。"),List.of()));
      if(question.question().matches("(?s).*(行情|走势).*" )&&question.question().matches("(?s).*(我的|本人).*(订单|资金|账户|余额|持仓).*"))return response("CLARIFICATION","MULTIPLE_TOPICS",AssistantRules.Intent.UNSUPPORTED,Map.of(),new AssistantPresentation.Result("请把公开行情和本人账户/订单分成两个问题查询，避免混合统计范围。",List.of(),List.of()));
      var intent=AssistantRules.classify(question.question());
      var selection=intent==AssistantRules.Intent.KNOWLEDGE?new AssistantSecurityResolver.Selection(null,"项目知识",""):intent==AssistantRules.Intent.MARKET?securities.resolveMarket(question.question(),question.securityId()):securities.resolve(question.question(),question.securityId());
      if(!selection.clarification().isBlank())return response("CLARIFICATION","SECURITY_AMBIGUOUS_OR_UNKNOWN",AssistantRules.Intent.UNSUPPORTED,Map.of(),new AssistantPresentation.Result(selection.clarification(),List.of(),List.of()));
      String mode="RULES",reason=model.configured()?"LOCAL_RECOGNITION":"MODEL_NOT_CONFIGURED";
      if(model.configured()){
        try{var detected=AssistantRules.validate(model.classify(question.question()));if(intent==AssistantRules.Intent.UNSUPPORTED)intent=detected;mode="MODEL_CLASSIFICATION";reason="";}
        catch(AssistantModelException failure){reason=failure.code();}
        catch(IllegalArgumentException invalid){reason="MODEL_INVALID_CLASSIFICATION";}
        catch(java.util.concurrent.TimeoutException timeout){reason="MODEL_TIMEOUT";}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();reason="MODEL_INTERRUPTED";}
        catch(Exception failure){reason="MODEL_NETWORK_OR_RESPONSE_ERROR";}
      }
      // 常见口语在模型故障或未配置时也能查询；这不是安全过滤，不阻止未知问法调用模型。
      if(intent==AssistantRules.Intent.UNSUPPORTED&&question.question().matches("(?s).*(多少钱|资金|能用|可以用).*"))intent=AssistantRules.Intent.ACCOUNT;
      if(intent==AssistantRules.Intent.UNSUPPORTED)return response("UNSUPPORTED","QUESTION_NOT_SUPPORTED",intent,Map.of(),new AssistantPresentation.Result(AssistantRules.explain(intent),List.of(),List.of()));
      if(intent==AssistantRules.Intent.KNOWLEDGE){
        var facts=knowledge.read(question.question());boolean found=Boolean.TRUE.equals(facts.get("found"));
        return response(found?facts.get("ragMode").toString():"UNSUPPORTED",found?facts.get("ragReason").toString():"KNOWLEDGE_NOT_FOUND",intent,facts,presentation.render(intent,facts,selection));
      }
      if(intent==AssistantRules.Intent.MARKET){
        selection=securities.resolveMarket(question.question(),question.securityId());
        if(!selection.clarification().isBlank())return response("CLARIFICATION","SECURITY_AMBIGUOUS_OR_UNKNOWN",intent,Map.of(),new AssistantPresentation.Result(selection.clarification(),List.of(),List.of()));
        AssistantTimeRangeResolver.Range range;
        try{range=timeRanges.resolve(question.question());}catch(IllegalArgumentException invalid){return response("CLARIFICATION","TIME_FILTER_NOT_SUPPORTED",intent,Map.of(),new AssistantPresentation.Result(invalid.getMessage(),List.of(),List.of()));}
        var facts=market.read(selection.securityId(),range);return response(mode,reason,intent,facts,presentation.render(intent,facts,selection));
      }
      if(question.question().matches("(?s).*(今天|昨天|本周|上周|本月|上月|近[一二三四五六七八九十0-9]+天).*"))return response("CLARIFICATION","TIME_FILTER_NOT_SUPPORTED",intent,Map.of(),new AssistantPresentation.Result("本人记录暂不按日期筛选；可以问当前活动订单或最近已结束订单。",List.of(),List.of()));
      var facts=reads.read(user,intent,selection.securityId(),question.orderId(),question.depositId());
      return response(mode,reason,intent,facts,presentation.render(intent,facts,selection));
    }finally{if(entered)active.remove(user);slots.release();}
  }
  /** 数据与展示分开，规则/模型分类/安全拒绝各有明确状态，不再误报为模型未配置。 */
  private Object response(String mode,String reason,AssistantRules.Intent intent,Map<String,Object> facts,AssistantPresentation.Result view){return Map.of("mode",mode,"fallbackReason",reason,"intent",intent.name(),"explanation",view.explanation(),"highlights",view.highlights(),"tables",view.tables(),"facts",facts,"sources",facts.getOrDefault("sources",List.of()),"asOf",System.currentTimeMillis());}
}
