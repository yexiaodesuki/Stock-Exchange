/* 公开知识原文：白名单加载、稳定章节身份和原文回退；额度动态展开，不将私密文件放入向量库。 */
package com.itranswarp.exchange.service.assistant.rag;
import com.itranswarp.exchange.service.DepositPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class KnowledgeDocumentService {
  public record Section(String id,String document,String title,List<String> aliases,String body,String version){}
  public record Chunk(String id,Section section,String text,int tokens){}
  private final List<Section> sections;private final DepositPolicy policy;
  /** 仅加载固定的三个资源，原文仍随Git及JAR发布。 */
  public KnowledgeDocumentService(DepositPolicy policy){this.policy=policy;var loaded=new ArrayList<Section>();for(String file:List.of("trading-rules.md","user-guide.md","deposits.md"))loaded.addAll(load(file));sections=List.copyOf(loaded);}
  /** 测试可注入不同版本文档，验证更新删除和重启复用，不修改正式资源。 */
  public KnowledgeDocumentService(DepositPolicy policy,List<Section> sections){this.policy=policy;this.sections=List.copyOf(sections);if(sections.isEmpty()||sections.size()>32)throw new IllegalArgumentException("知识章节数量无效");}
  /** 返回不可变原文，调用者不能改变当前索引所依据的知识。 */
  public List<Section> sections(){return sections;}
  /** 摘要包含真实内容和规则版本，更换模型或切片器时另外加入索引身份。 */
  public String contentHash(){return hash(RagHttp.JSON.valueToTree(sections).toString());}
  /** 关键词只是补召回；强术语用于无向量时明确原文降级，不声称为生成答案。 */
  public List<Section> lexical(String question,int limit){String q=question.toLowerCase(Locale.ROOT).replaceAll("\\s+","");return sections.stream().filter(s->score(s,q)>0).sorted(Comparator.<Section>comparingInt(s->score(s,q)).reversed().thenComparing(Section::id)).limit(limit).toList();}
  /** 同义词的命中长度只用于排名，不伪装为概率。 */
  private int score(Section section,String q){return section.aliases().stream().filter(q::contains).mapToInt(String::length).sum();}
  /** 当前公开额度只在回答时填充，索引不持久化过期配置值。 */
  public String expand(String text){return text.replace("{depositMinimum}",policy.minimum().toPlainString()).replace("{depositMaximum}",policy.maximum().toPlainString()).replace("{depositDaily}",policy.daily().toPlainString()).replace("{depositEnabled}",policy.enabled()?"已开启":"已关闭（不能提交新申请，既有申请仍可查询和继续处理）");}
  /** 真实分片必须属于已有章节并保持原文，不能把未知ID或篡改正文索引为知识。 */
  public List<Chunk> chunks(List<EmbeddingClient.Piece> pieces){
    if(pieces.isEmpty()||pieces.size()>256)throw new IllegalStateException("知识切片数量无效");var parents=new HashMap<String,Section>();sections.forEach(s->parents.put(s.id(),s));var chunks=new ArrayList<Chunk>();var ids=new HashSet<String>();var covered=new HashSet<String>();
    for(var piece:pieces){var section=parents.get(piece.parentId());if(section==null||piece.ordinal()<0||piece.tokens()<1||piece.tokens()>300||piece.text().length()>4000)throw new IllegalStateException("知识切片元数据无效");String prefix=section.document()+" · "+section.title()+"\n";if(!piece.text().startsWith(prefix)||piece.text().length()==prefix.length()||!section.body().contains(piece.text().substring(prefix.length())))throw new IllegalStateException("知识切片不属于原文");String id="k-"+hash(section.id()+":"+piece.ordinal()+":"+piece.text()).substring(0,32);if(!ids.add(id))throw new IllegalStateException("知识切片重复");chunks.add(new Chunk(id,section,piece.text(),piece.tokens()));covered.add(section.id());}
    if(covered.size()!=sections.size())throw new IllegalStateException("知识章节被遗漏");
    // 逐章核对有序覆盖，单个短片段不能冒充整章已完成；只允许空白间隙。
    for(var section:sections){var ordered=pieces.stream().filter(p->p.parentId().equals(section.id())).sorted(Comparator.comparingInt(EmbeddingClient.Piece::ordinal)).toList();int previous=-1,end=0,ordinal=0;String prefix=section.document()+" · "+section.title()+"\n";for(var piece:ordered){if(piece.ordinal()!=ordinal++)throw new IllegalStateException("知识切片顺序无效");String content=piece.text().substring(prefix.length());int start=piece.start();if(start<=previous||start<0||piece.end()<=start||piece.end()>section.body().length()||!section.body().substring(start,piece.end()).equals(content)||(start>end&&!section.body().substring(end,start).isBlank()))throw new IllegalStateException("知识切片存在缺口");previous=start;end=Math.max(end,piece.end());}if(!section.body().substring(end).isBlank())throw new IllegalStateException("知识正文尾部被丢弃");}
    return List.copyOf(chunks);
  }
  /** 固定SHA256用于内容寻址，不生成随机片段ID造成重复入库。 */
  public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
  /** 明确校验资源格式和大小，损坏时失败，不扫描其他目录或退回模型常识。 */
  private List<Section> load(String file){
    String text;try(var input=getClass().getResourceAsStream("/assistant/knowledge/"+file)){if(input==null)throw new IllegalStateException("知识资源缺失");var bytes=input.readNBytes(32769);if(bytes.length>32768)throw new IllegalStateException("知识资源过大");text=new String(bytes,StandardCharsets.UTF_8);}catch(java.io.IOException failure){throw new IllegalStateException("知识资源无法读取",failure);}
    String title=text.lines().filter(l->l.startsWith("# ")).findFirst().orElseThrow().substring(2).strip();var result=new ArrayList<Section>();int ordinal=0;
    for(var part:text.split("(?m)^## ")){if(part.startsWith("<!--"))continue;int end=part.indexOf('\n');if(end<0)throw new IllegalStateException("知识正文缺失");var header=part.substring(0,end).strip().split("\\|",-1);if(header.length!=2)throw new IllegalStateException("知识章节无效");var aliases=Arrays.stream(header[1].split(",")).map(String::strip).map(s->s.toLowerCase(Locale.ROOT)).filter(s->s.length()>=2).toList();String body=part.substring(end+1).strip();if(aliases.isEmpty()||body.isBlank()||body.matches("(?s).*\\{(?!depositMinimum}|depositMaximum}|depositDaily}|depositEnabled}).*"))throw new IllegalStateException("知识正文无效");result.add(new Section(file+":"+(ordinal++),title,header[0].strip(),aliases,body,hash(body).substring(0,12)));}
    return result;
  }
}
