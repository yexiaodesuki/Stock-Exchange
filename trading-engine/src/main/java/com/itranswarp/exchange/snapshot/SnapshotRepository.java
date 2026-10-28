/* MySQL 格式 2 快照仓储；追加完整快照，损坏候选可回退，数据库故障不得伪装成空库。 */
package com.itranswarp.exchange.snapshot;

import com.itranswarp.exchange.core.SecuritiesEngine;
import com.itranswarp.exchange.core.SecuritiesSnapshot;
import com.itranswarp.exchange.securities.SecurityCatalog;
import com.itranswarp.exchange.util.JsonUtil;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class SnapshotRepository {
  private final JdbcTemplate jdbc;
  private final SecurityCatalog catalog;

  /** 使用证券数据源，构造不执行建表或删库。 */
  public SnapshotRepository(DataSource source, SecurityCatalog catalog) {
    this.jdbc = new JdbcTemplate(source);
    this.catalog = catalog;
  }

  /** 查询候选后逐份校验，不捕获数据库连接错误；目录变更必须拒绝而非换规则全量重放。 */
  public SecuritiesEngine restore(long upper) {
    var records =
        jdbc.query(
            "SELECT * FROM engine_snapshots WHERE sequenceId<=? ORDER BY sequenceId DESC,id DESC"
                + " LIMIT 20",
            (rs, row) ->
                new Stored(
                    rs.getLong("sequenceId"),
                    rs.getInt("formatVersion"),
                    rs.getInt("catalogVersion"),
                    rs.getString("snapshotData"),
                    rs.getString("checksum")),
            upper);
    for (var record : records) {
      SecuritiesSnapshot value;
      try {
        value = decode(record);
      } catch (RuntimeException invalid) {
        continue;
      }
      var candidate = new SecuritiesEngine(catalog);
      if (!candidate.snapshot().catalogHash().equals(value.catalogHash()))
        throw new IllegalStateException("证券规则指纹改变，禁止按新规则恢复旧资产");
      try {
        candidate.restore(value);
        return candidate;
      } catch (RuntimeException invalid) {
        /* 结构不合法则尝试较旧候选。 */
      }
    }
    return new SecuritiesEngine(catalog);
  }

  /** 同位置有效内容必须一致；只追加、不覆盖历史，单条 INSERT 提交后才推进调用者保存位置。 */
  public void save(SecuritiesSnapshot snapshot) {
    var validator = new SecuritiesEngine(catalog);
    validator.restore(snapshot);
    String json = JsonUtil.writeJson(snapshot), checksum = SecuritiesEngine.checksum(json);
    var records =
        jdbc.query(
            "SELECT * FROM engine_snapshots WHERE sequenceId=? AND formatVersion=2 ORDER BY id"
                + " DESC",
            (rs, row) ->
                new Stored(
                    rs.getLong("sequenceId"),
                    rs.getInt("formatVersion"),
                    rs.getInt("catalogVersion"),
                    rs.getString("snapshotData"),
                    rs.getString("checksum")),
            snapshot.sequenceId());
    for (var record : records) {
      try {
        new SecuritiesEngine(catalog).restore(decode(record));
      } catch (RuntimeException invalid) {
        continue;
      }
      if (!record.checksum.equals(checksum)) throw new IllegalStateException("同一事件位置存在不同有效快照");
      return;
    }
    int count =
        jdbc.update(
            "INSERT INTO"
                + " engine_snapshots(sequenceId,formatVersion,catalogVersion,snapshotData,checksum,createdAt)"
                + " VALUES (?,2,1,?,?,?)",
            snapshot.sequenceId(),
            json,
            checksum,
            System.currentTimeMillis());
    if (count != 1) throw new IllegalStateException("快照没有完整写入");
  }

  /** 同时核对原文校验和、数据库列和 JSON 版本/位置。 */
  private SecuritiesSnapshot decode(Stored record) {
    if (record.format != 2
        || record.catalog != 1
        || !SecuritiesEngine.checksum(record.data).equals(record.checksum))
      throw new IllegalStateException("快照校验失败");
    var value = JsonUtil.readJson(record.data, SecuritiesSnapshot.class);
    if (value.formatVersion() != record.format
        || value.catalogVersion() != record.catalog
        || value.sequenceId() != record.sequence) throw new IllegalStateException("快照字段不一致");
    return value;
  }

  private record Stored(long sequence, int format, int catalog, String data, String checksum) {}
}
