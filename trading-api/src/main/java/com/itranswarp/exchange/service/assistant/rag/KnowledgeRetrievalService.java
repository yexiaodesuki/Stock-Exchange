/* 无重排序检索：向量与章节别名做RRF融合，只返回本地白名单原文，距离不是答案可信概率。 */
package com.itranswarp.exchange.service.assistant.rag;
import com.itranswarp.exchange.repository.ChromaKnowledgeRepository;
import com.itranswarp.exchange.service.assistant.rag.KnowledgeDocumentService.Chunk;
import java.util.*;

public final class KnowledgeRetrievalService {
  private final KnowledgeDocumentService documents;private final KnowledgeIndexService index;private final EmbeddingClient embedding;private final ChromaKnowledgeRepository repository;private final int limit;private final double maxDistance;
  /** 证据数与距离为可评测配置，不允许用户扩大检索范围。 */
  public KnowledgeRetrievalService(KnowledgeDocumentService documents,KnowledgeIndexService index,EmbeddingClient embedding,ChromaKnowledgeRepository repository,int limit,double maxDistance){if(limit<1||limit>3||!Double.isFinite(maxDistance)||maxDistance<=0||maxDistance>=1)throw new IllegalArgumentException("RAG召回配置无效");this.documents=documents;this.index=index;this.embedding=embedding;this.repository=repository;this.limit=limit;this.maxDistance=maxDistance;}
  /** 返回空代表证据不足；依赖异常交给上层标记原文降级，不冒充语义召回成功。 */
  public List<Chunk> retrieve(String question)throws Exception{
    var view=index.current();if(view==null)throw new IllegalStateException("RAG_INDEX_NOT_READY");var vector=embedding.embed(List.of(question),true,1500).get(0);var found=repository.query(view.collection(),vector,Math.min(8,view.chunks().size()),1000);
    var byId=new HashMap<String,Chunk>();view.chunks().forEach(c->byId.put(c.id(),c));var scores=new HashMap<String,Double>();int rank=0;
    for(var match:found){rank++;if(match.distance()<=maxDistance&&byId.containsKey(match.id()))scores.merge(match.id(),1.0/(60+rank),Double::sum);}
    rank=0;for(var section:documents.lexical(question,4)){rank++;for(var chunk:view.chunks())if(chunk.section().id().equals(section.id()))scores.merge(chunk.id(),1.0/(60+rank),Double::sum);}
    var ordered=scores.keySet().stream().sorted(Comparator.<String>comparingDouble(scores::get).reversed().thenComparing(s->s)).toList();var selected=new ArrayList<Chunk>();var parents=new HashSet<String>();int tokens=0;
    for(String id:ordered){var chunk=byId.get(id);if(!parents.add(chunk.section().id()))continue;if(tokens+chunk.tokens()>900)break;selected.add(chunk);tokens+=chunk.tokens();if(selected.size()==limit)break;}return List.copyOf(selected);
  }
}
