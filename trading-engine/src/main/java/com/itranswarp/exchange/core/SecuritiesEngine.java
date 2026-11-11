/* 五证券确定性核心：全局串行、候选状态提交、共享现金、分证券撮合清算及整体快照恢复。 */
package com.itranswarp.exchange.core;

import static com.itranswarp.exchange.core.EngineState.*;

import com.itranswarp.exchange.securities.SecuritiesProtocol.*;
import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.securities.output.ExecutionEnvelope;
import com.itranswarp.exchange.util.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class SecuritiesEngine {
  public enum Mode {
    LIVE,
    REPLAY
  }

  private static final long DEBT = 1;
  private final SecurityCatalog catalog;
  private final String catalogHash;
  private EngineState state = new EngineState();
  private boolean failed;
  private final StateValidator validator;
  private final SnapshotCodec snapshots;
  private final com.itranswarp.exchange.assets.AssetService assetService =
      new com.itranswarp.exchange.assets.AssetService(DEBT);
  private final com.itranswarp.exchange.order.OrderService orderService;

  /** 目录固定于本次运行；指纹防止相同目录版本下偷偷改变规则后重放。 */
  public SecuritiesEngine(SecurityCatalog catalog) {
    this.catalog = catalog;
    var clearing = new com.itranswarp.exchange.clearing.ClearingService();
    orderService =
        new com.itranswarp.exchange.order.OrderService(
            catalog,
            assetService,
            new com.itranswarp.exchange.match.MatchEngine(clearing),
            clearing);
    this.catalogHash = checksum(JsonUtil.writeJson(catalog.all()));
    validator = new StateValidator(catalog, DEBT);
    snapshots = new SnapshotCodec(catalog, catalogHash, validator);
    for (var definition : catalog.all()) state.markets.put(definition.securityId(), new Market());
  }

  /** 在候选副本执行完整事件，校验后一次替换；重放/重复不返回可发送的实时结果。 */
  public synchronized Optional<ExecutionResult> execute(SequencedEvent event, Mode mode) {
    if (failed) throw new IllegalStateException("引擎已失败");
    if (event.sequenceId() <= state.sequence) return Optional.empty();
    var prepared = prepare(event);
    install(prepared);
    return mode == Mode.LIVE ? Optional.of(prepared.result) : Optional.empty();
  }

  /** 只准备候选和完整输出，数据库确认前绝不安装资金变化。 */
  public synchronized Prepared prepare(SequencedEvent event) {
    if (failed) throw new IllegalStateException("引擎已失败，必须从持久历史恢复");
    if (event.sequenceId() <= state.sequence) throw new IllegalStateException("准备事件重复或过期");
    try {
      if (event.previousId() != state.sequence) throw new IllegalStateException("证券事件链断裂");
      var candidate = state.copy();
      ExecutionResult result = apply(candidate, event);
      if (!result.accepted()) candidate = state.copy();
      candidate.sequence = event.sequenceId();
      validator.validate(candidate);
      var trades = new ArrayList<ExecutionEnvelope.Trade>();
      for (int index = 0; index < result.trades().size(); index++) {
        var t = result.trades().get(index);
        trades.add(
            new ExecutionEnvelope.Trade(
                t.securityId(),
                t.sequenceId(),
                index,
                t.makerOrderId(),
                t.takerOrderId(),
                t.buyerId(),
                t.sellerId(),
                ((PlaceOrder) event.command()).direction(),
                t.price(),
                t.quantity(),
                t.makerRemaining(),
                t.takerRemaining(),
                event.createdAt()));
      }
      var books = new ArrayList<ExecutionEnvelope.Book>();
      if (result.accepted() && event.command() instanceof PlaceOrder p)
        books.add(book(candidate, p.securityId()));
      if (result.accepted() && event.command() instanceof CancelOrder c)
        books.add(book(candidate, c.securityId()));
      var output =
          new ExecutionEnvelope(
              event.sequenceId(),
              event.previousId(),
              event.userId(),
              event.uniqueId(),
              event.createdAt(),
              result.accepted(),
              result.reason(),
              result.orderId(),
              result.orders(),
              trades,
              books);
      return new Prepared(this, state.sequence, candidate, result, output);
    } catch (RuntimeException | Error failure) {
      failed = true;
      throw failure;
    }
  }

  /** 持久输出成功后才安装；过期或其他引擎的候选不能覆盖当前状态。 */
  public synchronized void install(Prepared prepared) {
    require(
        !failed && prepared.owner == this && state.sequence == prepared.previous, "候选状态已过期或归属错误");
    state = prepared.candidate;
  }

  public static final class Prepared {
    private final SecuritiesEngine owner;
    private final long previous;
    private final EngineState candidate;
    private final ExecutionResult result;
    private final ExecutionEnvelope output;

    /** 仅核心可以建立有效候选，外部不能伪造资金状态。 */
    private Prepared(
        SecuritiesEngine owner,
        long previous,
        EngineState candidate,
        ExecutionResult result,
        ExecutionEnvelope output) {
      this.owner = owner;
      this.previous = previous;
      this.candidate = candidate;
      this.result = result;
      this.output = output;
    }

    /** 暴露不可变持久输出，不暴露候选余额。 */
    public ExecutionEnvelope output() {
      return output;
    }
  }

  /** 分发受信事件；预期业务拒绝返回结果，内部不变量错误向上传播并停止引擎。 */
  private ExecutionResult apply(EngineState next, SequencedEvent event) {
    if (event.userId() <= DEBT) return reject(event, "INTERNAL_ACCOUNT");
    if (event.command() instanceof CreditCash credit) {
      assetService.creditCash(next, event.userId(), credit.amount());
      return accepted(event, null, List.of());
    }
    if (event.command() instanceof CreditPosition credit) {
      try {
        catalog.require(credit.securityId());
      } catch (IllegalArgumentException invalid) {
        return reject(event, "UNKNOWN_SECURITY");
      }
      assetService.creditPosition(next, event.userId(), credit.securityId(), credit.quantity());
      return accepted(event, null, List.of());
    }
    if (event.command() instanceof PlaceOrder place)
      return orderService.createOrder(next, event, place);
    if (event.command() instanceof CancelOrder cancel)
      return orderService.cancelOrder(next, event, cancel);
    throw new IllegalStateException("不支持的证券命令");
  }

  /** 只读聚合盘口；报价和股数不包含任何订单身份。 */
  public synchronized ExecutionEnvelope.Book book(long security) {
    catalog.require(security);
    return book(state, security);
  }

  /** 将候选盘口聚合为最多 50 档，保留该证券真实变更位置。 */
  private ExecutionEnvelope.Book book(EngineState current, long security) {
    var market = current.markets.get(security);
    return new ExecutionEnvelope.Book(
        security, market.sequence, market.price, levels(market.buy), levels(market.sell));
  }

  /** 同价格使用 compareTo 聚合，避免 BigDecimal 尾零导致重复价格档位。 */
  private List<ExecutionEnvelope.Level> levels(SortedSet<Order> orders) {
    var values = new ArrayList<ExecutionEnvelope.Level>();
    for (var order : orders) {
      if (!values.isEmpty() && values.get(values.size() - 1).price().compareTo(order.price) == 0) {
        var last = values.remove(values.size() - 1);
        values.add(
            new ExecutionEnvelope.Level(
                last.price(), number(last.quantity().add(order.remaining))));
      } else {
        if (values.size() == 50) break;
        values.add(new ExecutionEnvelope.Level(order.price, order.remaining));
      }
    }
    return List.copyOf(values);
  }

  /** 正常业务拒绝仍消耗事件位置，但不安装候选中的任何余额变化。 */
  private ExecutionResult reject(SequencedEvent event, String reason) {
    return new ExecutionResult(event.sequenceId(), false, reason, null, List.of());
  }

  /** 成功结果只有不可变数值，不包含仍会被后续事件修改的对象。 */
  private ExecutionResult accepted(
      SequencedEvent event, Long order, List<ExecutionResult.Trade> trades) {
    return new ExecutionResult(event.sequenceId(), true, "OK", order, trades);
  }

  /** 复制同一完整事件边界上的所有状态；排序和数值规范化使校验和可复现。 */
  public synchronized SecuritiesSnapshot snapshot() {
    if (failed) throw new IllegalStateException("失败引擎禁止保存快照");
    return snapshots.encode(state);
  }

  /** 在独立候选中恢复，验证通过才安装；不冻结、不撮合、不产生输出。 */
  public synchronized void restore(SecuritiesSnapshot snapshot) {
    require(
        !failed && state.sequence == 0 && state.cash.isEmpty() && state.positions.isEmpty(),
        "只能向全新引擎安装快照");
    state = snapshots.decode(snapshot);
  }

  /** 查询已完整应用的位置，重复事件不会再次改变它。 */
  public synchronized long sequence() {
    return state.sequence;
  }

  /** 查询只读账户视图；全部证券均显示零或实际值，不返回可变账户引用。 */
  public synchronized Account account(long user) {
    if (failed) throw new IllegalStateException("引擎已失败");
    var cash = state.cash.getOrDefault(user, new Balance());
    var positions =
        catalog.all().stream()
            .map(
                d -> {
                  var b =
                      state.positions.getOrDefault(
                          new PositionKey(user, d.securityId()), new Balance());
                  return new SecuritiesSnapshot.Position(
                      user, d.securityId(), b.available, b.frozen);
                })
            .toList();
    return new Account(new SecuritiesSnapshot.Cash(user, cash.available, cash.frozen), positions);
  }

  public record Account(
      SecuritiesSnapshot.Cash cash, List<SecuritiesSnapshot.Position> positions) {}

  /** 用标准 SHA-256 计算原始 UTF-8 JSON 校验和，不使用浮点或平台默认编码。 */
  public static String checksum(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** 不变量失败明确抛出；调用方不得吞掉错误继续执行或保存半状态。 */
  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
