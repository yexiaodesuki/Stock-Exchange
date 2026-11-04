/* 后台知识索引：完整校验后发布不可变代次，失败只降级知识问答；不成为交易启动的强依赖。 */
package com.itranswarp.exchange.service.assistant.rag;
import com.itranswarp.exchange.repository.ChromaKnowledgeRepository;
import com.itranswarp.exchange.service.assistant.rag.KnowledgeDocumentService.Chunk;
import java.util.*;
import java.util.concurrent.*;

public final class KnowledgeIndexService implements AutoCloseable {
  public record Index(ChromaKnowledgeRepository.Collection collection,String generation,List<Chunk> chunks){}
  private final boolean enabled;private final KnowledgeDocumentService documents;private final EmbeddingClient embedding;private final ChromaKnowledgeRepository repository;
  private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"knowledge-index");thread.setDaemon(true);return thread;});
  private volatile Index current;private volatile String state="DISABLED",reason="";
  /** 构造仅保存依赖，不连接模型或数据库，不能卡住API启动。 */
  public KnowledgeIndexService(boolean enabled,KnowledgeDocumentService documents,EmbeddingClient embedding,ChromaKnowledgeRepository repository){this.enabled=enabled;this.documents=documents;this.embedding=embedding;this.repository=repository;}
  /** 仅后台线程首次建库，依赖恢复后自动再试，不无限堆积构建任务。 */
  public void start(){if(enabled){state="PREPARING";worker.scheduleWithFixedDelay(this::refresh,0,15,TimeUnit.SECONDS);}}
  /** 失败不泄露端点或异常正文；当前代次仍可被查询端逐次检查依赖是否可用。 */
  public synchronized void refresh(){
    if(!enabled)return;
    try{
      String fingerprint=embedding.fingerprint();if(current!=null&&repository.complete(current.collection(),current.chunks(),current.generation())){state="READY";reason="";return;}current=null;
      var chunks=documents.chunks(embedding.split(documents.sections()));String generation=generation(documents.contentHash(),fingerprint,chunks);String name="securities-knowledge-"+generation.substring(0,32);var collection=repository.collection(name,generation);
      if(!repository.complete(collection,chunks,generation)){
        for(int offset=0;offset<chunks.size();offset+=16){var batch=chunks.subList(offset,Math.min(offset+16,chunks.size()));var vectors=embedding.embed(batch.stream().map(Chunk::text).toList(),false,15000);repository.upsert(collection,batch,vectors,generation);}
      }
      if(!repository.complete(collection,chunks,generation))throw new IllegalStateException("INDEX_INCOMPLETE");current=new Index(collection,generation,chunks);state="READY";reason="";
    }catch(InterruptedException interrupted){Thread.currentThread().interrupt();state="UNAVAILABLE";reason="INTERRUPTED";}catch(Exception unavailable){state="UNAVAILABLE";String code=unavailable.getMessage();reason=code!=null&&code.matches("[A-Z][A-Z0-9_]{0,64}")?code:"DEPENDENCY_UNAVAILABLE";}
  }
  /** 状态只公开就绪和片数，不泄露本地模型目录或密钥。 */
  public Map<String,Object> status(){var index=current;return Map.of("state",state,"reason",reason,"chunks",index==null?0:index.chunks().size(),"reranking",false);}
  /** 一次请求捕获同一个不可变索引，不把新旧集合混用。 */
  public Index current(){return current;}
  /** 将实际片段也纳入代次，避免切片算法变化后沿用旧集合，恢复测试复用同一身份算法。 */
  public static String generation(String content,String fingerprint,List<Chunk> chunks){return KnowledgeDocumentService.hash(content+":"+fingerprint+":token-chunks-300-40-v1:cosine-v2:"+RagHttp.JSON.valueToTree(chunks));}
  /** 只关闭自己的后台任务，不删除向量数据、模型或任何交易资源。 */
  public void close(){worker.shutdownNow();}
}
