<!-- 演示用户增量补齐说明：保留旧账户，通过持久计划和真实事件链配资，不清库、不重置资产。 -->
# 演示用户补齐

## 两种环境

- 全新环境：原有初始化逻辑创建 user1@example.com 至 user10@example.com。
- 已完成 demo-v1 的旧两用户环境：保留 buyer@example.com、seller@example.com 的身份、密码和交易状态，新增 user3@example.com 至 user10@example.com，名称为“用户三”至“用户十”。不会额外创建 user1/user2 别名。

本机旧环境随后已按明确授权统一登录邮箱：原 buyer（ID 1000）改为 user1@example.com，原 seller（ID 1001）改为 user2@example.com，并分别使用本地 SECURITIES_DEMO_USER1_PASSWORD、SECURITIES_DEMO_USER2_PASSWORD 重新生成密码哈希。此次只修改两条邮箱和两条密码记录，不改显示名、用户 ID、资产、订单、成交或事件，也不重新配资。这是已执行的一次性身份维护，不代表普通启动会自动改名或重置密码。

现在本机可用邮箱为 user1@example.com 至 user10@example.com。旧 buyer/seller 邮箱不再作为登录名；既有登录会话不会自动失效，可退出后重新登录。不要把身份证据或含密码哈希的本机备份上传仓库。

新增八人的密码分别取 build/.env.securities 中 SECURITIES_DEMO_USER3_PASSWORD 至 SECURITIES_DEMO_USER10_PASSWORD。每人一次性获得 100000 USD、证券 1001 至 1005 各 1000 股。修改配置文件不会重设已创建账户的密码。

## 执行过程与失败边界

DemoBootstrap.run 在演示模式下识别旧 demo-v1，调用 DemoUserExpansionService.expand。新环境的 demo-v2 路径不变，不需要额外建表脚本。

首次补齐只接受完整的旧两用户环境：原运行 COMPLETE、用户与档案恰好两份、邮箱恰为原买卖双方，没有其他未完成初始化。已有第三个注册用户的环境会拒绝自动接管，需要另行确定迁移方案。

补齐服务先取得 demo-users-v3 独占锁，再在同一事务内创建八个身份、运行记录及四十八个 PENDING 配资步骤。邮箱冲突或无效密码导致整体回滚，不留下部分身份。每人一个现金命令加五个持仓命令，没有挂单或撮合。

步骤使用固定标识，例如 demo-users-v3:003:CASH；DemoCreditService 通过 Kafka transfer Topic 发送，经定序落库和引擎执行。只有持久结果存在、引擎应用进度达到结果序号，并验证结果被接受且身份匹配后，才登记步骤 COMPLETE。发送结果不确定时仍重试同一标识，由现有事件链去重。

全部四十八步完成才标记运行 COMPLETE。失败保留计划与进度，下次重试从持久状态续跑；资产不是通过直接改数据库余额生成。完成后再次运行不发配资事件、不更改密码，也不会把用户后来交易后的余额恢复成初始值。

维护入口是 DemoUserExpansionService.main，必须显式传 --apply 才执行，供已确认的旧环境维护使用，不提供浏览器配资接口。普通首次启动仍只需 Compose 和 IDEA，无需运行维护入口。

## 本次实际验证

本地旧数据库已完成补齐：用户 2 → 10，事件 57 → 105，恰好增加 48 个配资事件；成交仍为 18 条。原两人账户内容、密码摘要、订单及成交相关计数前后一致。新增八人余额逐项核对正确，重复执行未增加事件；引擎重启后再次核对正确，八人通过真实登录接口验证成功。操作前已备份数据库，备份不加入仓库。

DemoUserExpansionTest 在隔离 MySQL 中覆盖成功与重复执行、执行后未登记进度的中断续跑、无效密码事务回滚、已有第三人时拒绝接管。测试注入受控执行器验证计划协调与持久化，不把它当作真实引擎资产测试；实际资产、重启和登录另由上述真实进程验证。

完整 test-phase2.ps1 回归：94 条测试记录，93 通过、1 跳过、0 失败、0 错误，打包成功。跳过项是独立开关控制的 SecuritiesDatabaseTest，不代表它已被执行。本次未重新运行浏览器图形端到端测试，页面资源未修改。
