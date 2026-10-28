#!/usr/bin/env bash
# 只读核对自动迁移结果；不插入资金、订单、快照或演示用户。
set -euo pipefail
# 使用容器环境密码，避免出现在命令行参数和日志里。
query() { mysql --default-character-set=utf8mb4 -hmysql -usecurities -N -B exchange_securities -e "$1"; }
[[ $(query "SELECT CONCAT(family,':',protocolVersion,':',catalogVersion) FROM storage_metadata WHERE id=1") == 'securities-v1:2:1' ]]
[[ $(query "SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1") == 1 ]]
[[ $(query "SELECT COUNT(*) FROM flyway_schema_history WHERE version='2' AND success=1") == 1 ]]
[[ $(query "SELECT COUNT(*) FROM flyway_schema_history WHERE version='3' AND success=1") == 1 ]]
[[ $(query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='exchange_securities' AND table_name='demo_steps'") == 1 ]]
[[ $(query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='exchange_securities' AND table_name IN ('engine_results','engine_output_checkpoint','event_outbox','projection_offsets','api_requests')") == 5 ]]
[[ $(query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='exchange_securities' AND table_name='ticks' AND column_name='tradeIndex'") == 1 ]]
[[ $(query 'SELECT COUNT(*) FROM securities') == 5 ]]
[[ $(query "SELECT GROUP_CONCAT(CONCAT(securityId,':',symbol) ORDER BY securityId SEPARATOR ',') FROM securities") == '1001:STAR,1002:GREEN,1003:CARE,1004:DAILY,1005:SAIL' ]]
[[ $(query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='exchange_securities' AND table_name IN ('users','user_profiles','password_auths','api_key_auths','events','unique_events','engine_snapshots','orders','clearings','match_details','transfer_logs','ticks','sec_bars','min_bars','hour_bars','day_bars','bootstrap_runs','storage_metadata','securities')") == 19 ]]
query 'SELECT securityId,symbol,name,quoteCurrency,priceTick,quantityStep,status FROM securities ORDER BY securityId'
echo '数据库结构、Flyway 历史、协议与五证券目录检查通过。'
