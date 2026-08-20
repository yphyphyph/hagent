/*
 HAgent 数据库初始化脚本

 说明：
 - 目标数据库：hagent（MySQL 8.0+，utf8mb4）
 - 用途：为 HAgent 独立应用初始化 AI 相关数据表
 - 表结构：chat_conversation / chat_message / chat_pending_action / knowledge_file /
            ai_prompt / system_config 字段定义与 huishace 源库保持一致（可直接复用存量数据迁移到 hagent）
 - 新增：hagent_user（Sa-Token 登录用户表）
 - 默认配置：system_config 插入 config_type='ai' 的默认配置项
 - 本脚本不执行任何事务 DDL，可重复执行（DROP IF EXISTS + CREATE）
*/

CREATE DATABASE IF NOT EXISTS `hagent` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `hagent`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for ai_prompt（AI提示词库表）
-- 字段定义与 huishace 源库 ai_prompt 保持一致
-- ----------------------------
DROP TABLE IF EXISTS `ai_prompt`;
CREATE TABLE `ai_prompt`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '提示词ID',
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  `category` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'general' COMMENT '分类(writing/seo/analysis/general 等)',
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '提示词内容(支持占位符)',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '描述',
  `variables` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '变量占位符列表(逗号分隔)',
  `builtin` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否内置(0=否 1=是,内置不可删除)',
  `use_count` int NOT NULL DEFAULT 0 COMMENT '使用次数',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '排序',
  `status` int NOT NULL DEFAULT 1 COMMENT '状态(1=启用 0=禁用)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `created_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '创建人',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `updated_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '更新人',
  `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除(0=未删除 1=已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_category`(`category` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_sort_order`(`sort_order` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI提示词库表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for chat_conversation（AI聊天会话表）
-- 字段定义与 huishace 源库 chat_conversation 保持一致
-- ----------------------------
DROP TABLE IF EXISTS `chat_conversation`;
CREATE TABLE `chat_conversation`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '新对话' COMMENT '会话标题',
  `scene` int NOT NULL DEFAULT 1 COMMENT '场景(1=通用问答 2=RAG知识问答 3=数据分析)',
  `system_prompt` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '系统提示词',
  `rag_file_ids` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'RAG文件ID列表(逗号分隔)',
  `model_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '模型名称',
  `temperature` decimal(3, 2) NULL DEFAULT 0.70 COMMENT '温度参数',
  `max_tokens` int NULL DEFAULT 4096 COMMENT '最大Token数',
  `total_tokens` int NOT NULL DEFAULT 0 COMMENT '累计Token消耗',
  `message_count` int NOT NULL DEFAULT 0 COMMENT '消息数量',
  `last_message_at` datetime NULL DEFAULT NULL COMMENT '最后消息时间',
  `status` int NOT NULL DEFAULT 1 COMMENT '状态(1=正常 0=已归档)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `created_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '创建人',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `updated_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '更新人',
  `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除(0=未删除 1=已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_scene`(`scene` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_last_message_at`(`last_message_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI聊天会话表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for chat_message（AI聊天消息表）
-- 字段定义与 huishace 源库 chat_message 保持一致
-- ----------------------------
DROP TABLE IF EXISTS `chat_message`;
CREATE TABLE `chat_message`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL COMMENT '会话ID',
  `role` int NOT NULL COMMENT '角色(1=用户 2=AI助手 3=系统 4=工具)',
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '消息内容',
  `message_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT 'text' COMMENT '消息类型(text/image/tool)',
  `tool_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '工具名称',
  `tool_payload` json NULL COMMENT '工具调用参数(JSON)',
  `token_count` int NOT NULL DEFAULT 0 COMMENT 'Token数量',
  `status` int NOT NULL DEFAULT 1 COMMENT '状态(1=正常 0=已撤回)',
  `error_message` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '错误信息',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `created_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '创建人',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `updated_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '更新人',
  `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除(0=未删除 1=已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_conversation_id`(`conversation_id` ASC) USING BTREE,
  INDEX `idx_role`(`role` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_created_at`(`created_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI聊天消息表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for chat_pending_action（AI待确认操作表）
-- 字段定义与 huishace 源库 chat_pending_action 保持一致
-- ----------------------------
DROP TABLE IF EXISTS `chat_pending_action`;
CREATE TABLE `chat_pending_action`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '操作ID',
  `conversation_id` bigint NOT NULL COMMENT '会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `action_type` int NOT NULL COMMENT '操作类型(1=创建订单 2=修改订单 3=删除订单 4=查询数据)',
  `target_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '目标类型',
  `target_id` bigint NULL DEFAULT NULL COMMENT '目标ID',
  `payload_json` json NULL COMMENT '操作参数(JSON)',
  `preview_text` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '预览文本',
  `status` int NOT NULL DEFAULT 1 COMMENT '状态(1=待确认 2=已确认 3=已取消 4=已执行 5=已过期)',
  `expire_time` datetime NOT NULL COMMENT '过期时间',
  `confirmed_at` datetime NULL DEFAULT NULL COMMENT '确认时间',
  `executed_at` datetime NULL DEFAULT NULL COMMENT '执行时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `created_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '创建人',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `updated_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '更新人',
  `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除(0=未删除 1=已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_conversation_id`(`conversation_id` ASC) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_expire_time`(`expire_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI待确认操作表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for knowledge_file（知识库文件表）
-- 字段定义与 huishace 源库 knowledge_file 保持一致
-- ----------------------------
DROP TABLE IF EXISTS `knowledge_file`;
CREATE TABLE `knowledge_file`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `file_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件名',
  `file_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件URL',
  `object_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '对象存储路径',
  `file_size` bigint NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
  `file_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件类型(pdf/docx/txt/md)',
  `file_ext` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件扩展名',
  `vector_status` int NOT NULL DEFAULT 1 COMMENT '向量化状态(1=待处理 2=处理中 3=已完成 4=失败)',
  `vector_version` int NOT NULL DEFAULT 1 COMMENT '向量版本号',
  `chunk_count` int NOT NULL DEFAULT 0 COMMENT '分块数量',
  `token_count` int NOT NULL DEFAULT 0 COMMENT 'Token数量',
  `error_message` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '错误信息',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `created_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '创建人',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `updated_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '更新人',
  `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除(0=未删除 1=已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_vector_status`(`vector_status` ASC) USING BTREE,
  INDEX `idx_file_type`(`file_type` ASC) USING BTREE,
  INDEX `idx_created_at`(`created_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '知识库文件表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for system_config（系统配置表）
-- 字段定义与 huishace 源库 system_config 保持一致
-- ----------------------------
DROP TABLE IF EXISTS `system_config`;
CREATE TABLE `system_config`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '配置ID',
  `config_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '配置键',
  `config_value` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '配置值',
  `config_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'system' COMMENT '配置类型(system/ai/website/social)',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '描述',
  `is_public` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否公开(0=否 1=是)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `created_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '创建人',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `updated_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '更新人',
  `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除(0=未删除 1=已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_config_key`(`config_key` ASC) USING BTREE,
  INDEX `idx_config_type`(`config_type` ASC) USING BTREE,
  INDEX `idx_is_public`(`is_public` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '系统配置表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of system_config（config_type='ai' 的默认配置）
-- 注意：
--  1. 各 api-key / base-url 为占位，需在首次部署后通过环境变量或运行时/控制台配置项替换；
--  2. config_key 唯一索引，重复执行本脚本不会追加重复配置；
--  3. 默认模型与调用方式参考 huishace 源库 2.6 / 2.6.1 节（阿里云 DashScope 兼容模式）。
-- ----------------------------
INSERT INTO `system_config` (`config_key`, `config_value`, `config_type`, `description`, `is_public`) VALUES
-- 公共默认参数
('ai.default_model',        'qwen-plus',                  'ai', '默认对话模型名称', 1),
('ai.default_temperature',  '0.7',                        'ai', '默认温度参数(0-2)', 1),
('ai.default_max_tokens',   '4096',                       'ai', '默认最大Token输出数', 1),
('ai.system_prompt',        '你是一个智能助手，请用中文、专业、友好的方式回答用户问题。', 'ai', '默认系统提示词', 0),
('ai.enable_rag',           'true',                       'ai', '是否启用RAG知识库检索(布尔)', 1),
('ai.pending_action_expire', '600',                       'ai', '待确认操作过期时间(单位秒，默认10分钟)', 0),
('ai.vector-index',         'hagent_embedding',        'ai', '向量索引名称(向量数据库/索引集合名)', 0),
-- Chat 对话模型
('ai.chat.api-key',         '',                            'ai', '对话模型API密钥[运行时或环境变量填]', 0),
('ai.chat.base-url',        'https://dashscope.aliyuncs.com/compatible-mode', 'ai', '对话模型API基础地址(OpenAI兼容)', 0),
('ai.chat.model',           'qwen-plus',                  'ai', '对话模型名称', 0),
-- Embedding 向量化模型
('ai.embedding.api-key',    '',                            'ai', '向量化模型API密钥[运行时或环境变量填]', 0),
('ai.embedding.base-url',   'https://dashscope.aliyuncs.com/compatible-mode', 'ai', '向量化模型API基础地址', 0),
('ai.embedding.model',      'text-embedding-v3',          'ai', '向量化模型名称', 0),
-- Vision 视觉(多模态)模型
('ai.vision.api-key',       '',                            'ai', '视觉模型API密钥[运行时或环境变量填]', 0),
('ai.vision.base-url',      'https://dashscope.aliyuncs.com/compatible-mode', 'ai', '视觉模型API基础地址', 0),
('ai.vision.model',         'qwen-vl-plus',               'ai', '视觉模型名称', 0);

-- ----------------------------
-- Table structure for hagent_user（Sa-Token 登录用户表）
-- 说明：
--  - password 存储 BCrypt 哈希（Spring Security BCryptPasswordEncoder，长度60字符，VARCHAR(100) 足够）；
--  - deleted 用于 MyBatis-Plus 逻辑删除；
--  - role 使用字符串便于扩展多角色（默认 'admin'，可用逗号分隔如 'admin,user'）。
-- ----------------------------
DROP TABLE IF EXISTS `hagent_user`;
CREATE TABLE `hagent_user`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '登录用户名',
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '登录密码(BCrypt哈希)',
  `nickname` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '昵称',
  `avatar` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '头像URL',
  `role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'admin' COMMENT '角色(默认admin，多角色可用逗号分隔)',
  `status` int NOT NULL DEFAULT 1 COMMENT '状态(1=正常 0=禁用)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除(0=未删除 1=已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_username`(`username` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Sa-Token登录用户表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of hagent_user
-- 初始管理员 admin，密码 BCrypt 哈希对应明文为 'admin123'
-- 注意：生产环境首次登录后请立即修改默认密码
-- BCrypt 哈希($2a$10$...)：对明文 'admin123'，算法 BCrypt password encoder
-- ----------------------------
INSERT INTO `hagent_user` (`username`, `password`, `nickname`, `avatar`, `role`, `status`) VALUES
('admin', '$2a$10$eZ2hjlch.kJMUk.96RTJ6Ormwhx.C8Ryw3MKerU.RodxPhtCoNkFa', '系统管理员', NULL, 'admin', 1);

SET FOREIGN_KEY_CHECKS = 1;