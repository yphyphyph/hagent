/*
 HAgent 存量数据迁移脚本

 说明：
 - 将现有 huishace 数据库中的 AI 类存量数据导入 hagent：
     chat_conversation / chat_message / chat_pending_action /
     knowledge_file / ai_prompt / system_config(仅 config_type='ai')
 - 跨库访问：INSERT ... SELECT 由 MySQL 在同一实例内直接访问 `hagent`.`xxx`
 - 前提：
     1) 源库 `hagent` 与目标库 `hagent` 位于同一 MySQL 实例；
     2) 目标表结构已由 hui-agent-schema.sql 创建完成；
     3) 执行账号具备两个库的读写权限。
 - 主外键/唯一键兼容说明：
     * 表之间未建立物理外键，均为通过 id 关联的逻辑外键，跨库迁移无需处理约束；
     * system_config.config_key 存在唯一索引 uk_config_key，目标库已预置同键
       默认配置，故使用 ON DUPLICATE KEY UPDATE 以"已存在则更新、不存在则插入"，
       避免重复键冲突，同时保证迁移后以源库值为准（仅同步 ai 类型）。
     * chat_conversation / chat_message / chat_pending_action 之间保持 id 递增，
       采用原样搬运，会话与消息的 id 一一对应关系得到保留。
 - 幂等性：重复执行不会产生重复数据（system_config 靠唯一键，其余表无唯一键，
   如需重跑清在目标库先 truncate 对应表）。

 使用方式：在目标库 hagent 下执行（或带库名执行）。
*/

USE `hagent`;
SET NAMES utf8mb4;

-- ----------------------------
-- 1. 迁移 AI 聊天会话表
-- ----------------------------
INSERT INTO `hagent`.`chat_conversation`
    (`id`, `user_id`, `title`, `scene`, `system_prompt`, `rag_file_ids`,
     `model_name`, `temperature`, `max_tokens`, `total_tokens`, `message_count`,
     `last_message_at`, `status`, `created_at`, `created_by`, `updated_at`,
     `updated_by`, `deleted`)
SELECT
    `id`, `user_id`, `title`, `scene`, `system_prompt`, `rag_file_ids`,
    `model_name`, `temperature`, `max_tokens`, `total_tokens`, `message_count`,
    `last_message_at`, `status`, `created_at`, `created_by`, `updated_at`,
    `updated_by`, `deleted`
FROM `hagent`.`chat_conversation`;

-- ----------------------------
-- 2. 迁移 AI 聊天消息表
-- ----------------------------
INSERT INTO `hagent`.`chat_message`
    (`id`, `conversation_id`, `role`, `content`, `message_type`, `tool_name`,
     `tool_payload`, `token_count`, `status`, `error_message`, `created_at`,
     `created_by`, `updated_at`, `updated_by`, `deleted`)
SELECT
    `id`, `conversation_id`, `role`, `content`, `message_type`, `tool_name`,
    `tool_payload`, `token_count`, `status`, `error_message`, `created_at`,
    `created_by`, `updated_at`, `updated_by`, `deleted`
FROM `hagent`.`chat_message`;

-- ----------------------------
-- 3. 迁移 AI 待确认操作表
-- ----------------------------
INSERT INTO `hagent`.`chat_pending_action`
    (`id`, `conversation_id`, `user_id`, `action_type`, `target_type`, `target_id`,
     `payload_json`, `preview_text`, `status`, `expire_time`, `confirmed_at`,
     `executed_at`, `created_at`, `created_by`, `updated_at`, `updated_by`, `deleted`)
SELECT
    `id`, `conversation_id`, `user_id`, `action_type`, `target_type`, `target_id`,
    `payload_json`, `preview_text`, `status`, `expire_time`, `confirmed_at`,
    `executed_at`, `created_at`, `created_by`, `updated_at`, `updated_by`, `deleted`
FROM `hagent`.`chat_pending_action`;

-- ----------------------------
-- 4. 迁移知识库文件表
-- ----------------------------
INSERT INTO `hagent`.`knowledge_file`
    (`id`, `file_name`, `file_url`, `object_name`, `file_size`, `file_type`,
     `file_ext`, `vector_status`, `vector_version`, `chunk_count`, `token_count`,
     `error_message`, `created_at`, `created_by`, `updated_at`, `updated_by`, `deleted`)
SELECT
    `id`, `file_name`, `file_url`, `object_name`, `file_size`, `file_type`,
    `file_ext`, `vector_status`, `vector_version`, `chunk_count`, `token_count`,
    `error_message`, `created_at`, `created_by`, `updated_at`, `updated_by`, `deleted`
FROM `hagent`.`knowledge_file`;

-- ----------------------------
-- 5. 迁移 AI 提示词库表
-- ----------------------------
INSERT INTO `hagent`.`ai_prompt`
    (`id`, `title`, `category`, `content`, `description`, `variables`, `builtin`,
     `use_count`, `sort_order`, `status`, `created_at`, `created_by`, `updated_at`,
     `updated_by`, `deleted`)
SELECT
    `id`, `title`, `category`, `content`, `description`, `variables`, `builtin`,
    `use_count`, `sort_order`, `status`, `created_at`, `created_by`, `updated_at`,
    `updated_by`, `deleted`
FROM `hagent`.`ai_prompt`;

-- ----------------------------
-- 6. 迁移系统配置(仅 config_type='ai')
--    因为 config_key 有唯一索引，使用 ON DUPLICATE KEY UPDATE 同步目标配置值
-- ----------------------------
INSERT INTO `hagent`.`system_config`
    (`config_key`, `config_value`, `config_type`, `description`, `is_public`,
     `created_at`, `created_by`, `updated_at`, `updated_by`, `deleted`)
SELECT
    `config_key`, `config_value`, `config_type`, `description`, `is_public`,
    `created_at`, `created_by`, `updated_at`, `updated_by`, `deleted`
FROM `hagent`.`system_config`
WHERE `config_type` = 'ai'
ON DUPLICATE KEY UPDATE
    `config_value` = VALUES(`config_value`),
    `description`  = VALUES(`description`),
    `is_public`    = VALUES(`is_public`),
    `updated_at`   = CURRENT_TIMESTAMP;