#!/usr/bin/env bash
# 幂等创建证券专属 Topic；验证分区和副本，不修改旧 Topic 或消费位置。
set -euo pipefail
for stage in sequence transfer trade tick; do
  topic="securities.v1.$stage"
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --create --if-not-exists \
    --topic "$topic" --partitions 1 --replication-factor 1 --config retention.ms=-1
  description=$(/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --describe --topic "$topic")
  if ! printf '%s\n' "$description" | grep -Eq 'PartitionCount: 1[[:space:]]+ReplicationFactor: 1([[:space:]]|$)'; then
    printf '证券 Topic 配置不兼容，拒绝继续：%s\n' "$topic" >&2
    exit 1
  fi
  if ! printf '%s\n' "$description" | grep -q 'retention.ms=-1'; then
    printf '证券 Topic 缺少无限保留配置，拒绝继续：%s\n' "$topic" >&2
    exit 1
  fi
done
