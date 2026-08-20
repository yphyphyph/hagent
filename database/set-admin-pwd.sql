-- 重置 HAgent 初始管理员密码为明文 admin123 对应的 BCrypt 哈希
USE `hagent`;
UPDATE `hagent_user` SET `password` = '$2a$10$eZ2hjlch.kJMUk.96RTJ6Ormwhx.C8Ryw3MKerU.RodxPhtCoNkFa'
WHERE `username` = 'admin';