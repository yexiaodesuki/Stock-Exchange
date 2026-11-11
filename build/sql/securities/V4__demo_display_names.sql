-- 统一旧演示账户的显示名；只更新指定邮箱的旧称呼，不改用户 ID、密码或任何交易数据。
UPDATE user_profiles SET name = '用户一'
WHERE email = 'user1@example.com' AND name IN ('演示买方', '买方');
UPDATE user_profiles SET name = '用户二'
WHERE email = 'user2@example.com' AND name IN ('演示卖方', '卖方');
