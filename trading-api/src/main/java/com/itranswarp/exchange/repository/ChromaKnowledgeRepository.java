/* Chroma索引适配：只访问本项目内容寻址集合，不提供用户任意集合、SQL、URL或删除入口。 */
package com.itranswarp.exchange.repository;
import com.itranswarp.exchange.service.assistant.rag.*;
import com.itranswarp.exchange.service.assistant.rag.KnowledgeDocumentService.Chunk;
import java.util.*;

public final class ChromaKnowledgeRepository {
  public record Collection(String id,String name){}
  public record Match(String id,double distance){}
  private final String base;
  /** 端点固定本机，租户和数据库为独立服务的默认空间，不对外开放管理接口。 */
  public ChromaKnowledgeRepository(String endpoint){base=RagHttp.local(endpoint)+"/api/v2/tenants/default_tenant/databases/default_database/collections";}
  /** 只创建自己的哈希集合；名称碰撞或元数据不匹配时拒绝覆盖。 */
  public Collection collection(String name,String generation)throws Exception{
    if(!name.matches("securities-knowledge-[a-f0-9]{32}"))throw new IllegalArgumentException("知识集合名称无效");
    var row=RagHttp.exchange(base+"/"+name,"GET",null,null,1500,8192);
    if(row.isMissingNode())row=RagHttp.exchange(base,"POST",Map.of("name",name,"get_or_create",true,"metadata",Map.of("generation",generation),"configuration",Map.of("hnsw",Map.of("space","cosine"))),null,1500,8192);
    String id=row.path("id").asText();if(!id.matches("[a-f0-9-]{36}")||!generation.equals(row.path("metadata").path("generation").asText())||!"cosine".equals(row.path("configuration_json").path("hnsw").path("space").asText()))throw new IllegalStateException("CHROMA_COLLECTION_IDENTITY");return new Collection(id,name);
  }
  /** 每批显式写入本地模型向量，Chroma不能替换成默认嵌入；重复构建同ID幂等。 */
  public void upsert(Collection collection,List<Chunk> chunks,List<List<Double>> vectors,String generation)throws Exception{
    if(chunks.isEmpty()||chunks.size()>32||vectors.size()!=chunks.size())throw new IllegalArgumentException("知识索引批次无效");
    var body=Map.of("ids",chunks.stream().map(Chunk::id).toList(),"documents",chunks.stream().map(Chunk::text).toList(),"embeddings",vectors,"metadatas",chunks.stream().map(c->Map.of("docId",c.section().id(),"section",c.section().title(),"version",c.section().version(),"generation",generation)).toList());
    RagHttp.exchange(base+"/"+collection.id()+"/upsert","POST",body,null,3000,8192);
  }
  /** 同时校验数量、ID、正文及代次，不能把部分构建或错版本索引当作就绪。 */
  public boolean complete(Collection collection,List<Chunk> chunks,String generation)throws Exception{
    var count=RagHttp.exchange(base+"/"+collection.id()+"/count","GET",null,null,1500,4096);if(!count.isIntegralNumber()||count.asInt()!=chunks.size())return false;
    var rows=RagHttp.exchange(base+"/"+collection.id()+"/get","POST",Map.of("ids",chunks.stream().map(Chunk::id).toList(),"include",List.of("documents","metadatas")),null,2000,1048576);
    var expected=new HashMap<String,Chunk>();chunks.forEach(c->expected.put(c.id(),c));var ids=rows.path("ids");if(!ids.isArray()||ids.size()!=chunks.size())return false;
    for(int i=0;i<ids.size();i++){var chunk=expected.remove(ids.get(i).asText());if(chunk==null||!chunk.text().equals(rows.path("documents").path(i).asText())||!generation.equals(rows.path("metadatas").path(i).path("generation").asText()))return false;}return expected.isEmpty();
  }
  /** 相似度只提供候选排序；调用方仍只接受当前白名单中的片段ID和有限余弦距离。 */
  public List<Match> query(Collection collection,List<Double> vector,int limit,int timeout)throws Exception{
    if(vector.size()!=512||limit<1||limit>8)throw new IllegalArgumentException("向量查询无效");
    var response=RagHttp.exchange(base+"/"+collection.id()+"/query","POST",Map.of("query_embeddings",List.of(vector),"n_results",limit,"include",List.of("distances")),null,timeout,32768);var ids=response.path("ids").path(0);var distances=response.path("distances").path(0);
    if(!ids.isArray()||!distances.isArray()||ids.size()!=distances.size()||ids.size()>limit)throw new IllegalStateException("CHROMA_QUERY_INVALID");var matches=new ArrayList<Match>();for(int i=0;i<ids.size();i++){double distance=distances.get(i).asDouble(Double.NaN);if(!Double.isFinite(distance)||distance< -0.00001||distance>2.00001)throw new IllegalStateException("CHROMA_DISTANCE_INVALID");matches.add(new Match(ids.get(i).asText(),Math.max(0,distance)));}return List.copyOf(matches);
  }
}
