#!/usr/bin/env bash
# 只创建/删除严格命名的本次临时测试库；不能把开发库 exchange_securities 作为目标。
set -euo pipefail
action=${1:?missing action}
database=${2:?missing database}
[[ "$database" =~ ^exchange_securities_test_[a-f0-9]{32}$ ]] || { echo '拒绝操作非临时测试数据库' >&2; exit 2; }
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
case "$action" in
  create) mysql -uroot -e "CREATE DATABASE $database CHARACTER SET utf8mb4; GRANT ALL ON $database.* TO 'securities'@'%';";;
  drop) mysql -uroot -e "DROP DATABASE $database; REVOKE ALL ON $database.* FROM 'securities'@'%';";;
  *) echo '测试库操作无效' >&2; exit 2;;
esac
