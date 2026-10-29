/* 入金用例：绑定已认证用户、严格解析金额、登记申请；调用者不能指定证券或配资命令。 */
package com.itranswarp.exchange.service;

import com.itranswarp.exchange.repository.DepositRepository;
import java.time.*;
import java.util.List;

public final class DepositService {
  private final DepositRepository repository;
  private final DepositPolicy policy;
  private final Clock clock;

  /** 注入时钟验证跨日行为；生产使用 UTC，不依赖宿主机时区。 */
  public DepositService(DepositRepository repository,DepositPolicy policy,Clock clock) {
    this.repository=repository;this.policy=policy;this.clock=clock;
  }

  /** 返回持久申请，不把 HTTP 202 或 Kafka ACK 当作到账成功。 */
  public DepositRepository.Deposit create(long user,String key,String amount) {
    return repository.create(user,key,policy.amount(amount),LocalDate.ofInstant(clock.instant(),ZoneOffset.UTC),clock.millis(),policy);
  }

  /** 根据已验证身份查本人记录，非法 UUID 不进入模糊查询。 */
  public DepositRepository.Deposit get(long user,String id) {
    if(id==null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new IllegalArgumentException("入金编号无效");
    return repository.get(user,id);
  }

  /** 本人历史有界分页，状态由后台确认而非查询端猜测。 */
  public List<DepositRepository.Deposit> list(long user,int page,int limit) {
    return repository.list(user,page,limit);
  }

  /** 页面展示实际生效的限制，不能依赖前端硬编码进行风控。 */
  public DepositPolicy policy() { return policy; }
}
