-- MySQL dump 10.13  Distrib 8.0.42, for Win64 (x86_64)
--
-- Host: 127.0.0.1    Database: hagent
-- ------------------------------------------------------
-- Server version	8.0.42

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `ai_prompt`
--

DROP TABLE IF EXISTS `ai_prompt`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_prompt` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '提示词ID',
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  `category` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'general' COMMENT '分类(writing/seo/analysis/general 等)',
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '提示词内容(支持占位符)',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `variables` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '变量占位符列表(逗号分隔)',
  `builtin` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否内置(0=否 1=是,内置不可删除)',
  `use_count` int NOT NULL DEFAULT '0' COMMENT '使用次数',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序',
  `status` int NOT NULL DEFAULT '1' COMMENT '状态(1=启用 0=禁用)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `created_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `updated_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '更新人',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除(0=未删除 1=已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_category` (`category`) USING BTREE,
  KEY `idx_status` (`status`) USING BTREE,
  KEY `idx_sort_order` (`sort_order`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='AI提示词库表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `ai_prompt`
--

LOCK TABLES `ai_prompt` WRITE;
/*!40000 ALTER TABLE `ai_prompt` DISABLE KEYS */;
INSERT INTO `ai_prompt` VALUES (1,'文章写作助手','writing','你是一名资深内容创作者。请围绕主题「{topic}」，以{style}的风格撰写一篇约{length}字的文章，要求结构清晰、逻辑连贯、有观点。','根据主题、风格、字数生成文章草稿','topic,style,length',1,1,1,1,'2026-07-24 10:45:21','system','2026-07-24 10:50:18',NULL,0),(2,'SEO 优化建议','seo','你是一名 SEO 专家。请针对以下内容给出 SEO 优化建议，包括标题优化、关键词布局、摘要与标签建议：\n\n{content}','为文章内容生成 SEO 优化建议','content',1,0,2,1,'2026-07-24 10:45:21','system','2026-07-24 10:45:21',NULL,0),(3,'运营数据分析','analysis','你是一名数据分析师。请基于用户的自然语言问题「{question}」，分析业务数据并给出结论与可执行建议。','自然语言问数与运营分析','question',1,0,3,1,'2026-07-24 10:45:21','system','2026-07-24 10:45:21',NULL,0),(4,'测试提示词-改','general','新内容','d',NULL,0,1,0,1,'2026-08-20 23:03:27',NULL,'2026-08-20 23:03:38',NULL,1);
/*!40000 ALTER TABLE `ai_prompt` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `chat_conversation`
--

DROP TABLE IF EXISTS `chat_conversation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_conversation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '新对话' COMMENT '会话标题',
  `scene` int NOT NULL DEFAULT '1' COMMENT '场景(1=通用问答 2=RAG知识问答 3=数据分析)',
  `system_prompt` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '系统提示词',
  `rag_file_ids` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'RAG文件ID列表(逗号分隔)',
  `model_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型名称',
  `temperature` decimal(3,2) DEFAULT '0.70' COMMENT '温度参数',
  `max_tokens` int DEFAULT '4096' COMMENT '最大Token数',
  `total_tokens` int NOT NULL DEFAULT '0' COMMENT '累计Token消耗',
  `message_count` int NOT NULL DEFAULT '0' COMMENT '消息数量',
  `last_message_at` datetime DEFAULT NULL COMMENT '最后消息时间',
  `status` int NOT NULL DEFAULT '1' COMMENT '状态(1=正常 0=已归档)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `created_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `updated_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '更新人',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除(0=未删除 1=已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_user_id` (`user_id`) USING BTREE,
  KEY `idx_scene` (`scene`) USING BTREE,
  KEY `idx_status` (`status`) USING BTREE,
  KEY `idx_last_message_at` (`last_message_at`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=21 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='AI聊天会话表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chat_conversation`
--

LOCK TABLES `chat_conversation` WRITE;
/*!40000 ALTER TABLE `chat_conversation` DISABLE KEYS */;
INSERT INTO `chat_conversation` VALUES (1,1,'你好',1,NULL,NULL,NULL,0.70,4096,211,4,'2026-08-06 09:05:07',1,'2026-07-13 23:54:52','1','2026-08-06 09:05:07','1',0),(2,1,'你是谁？',1,NULL,NULL,NULL,0.70,4096,214,5,'2026-07-14 11:47:46',1,'2026-07-13 23:56:33','1','2026-07-13 23:56:36','1',0),(3,1,'你发送一个表情给我，用表格',1,NULL,NULL,NULL,0.70,4096,482,3,'2026-07-15 23:03:52',1,'2026-07-14 00:07:44','1','2026-07-15 23:03:52','1',0),(4,1,'发一个简单的表格内容给我',1,NULL,NULL,NULL,0.70,4096,57,1,'2026-07-14 00:08:35',1,'2026-07-14 00:08:33','1','2026-07-14 00:08:36','1',0),(5,1,'你发送一个表情给我，用表格',1,NULL,NULL,NULL,0.70,4096,47,1,'2026-07-14 00:15:56',1,'2026-07-14 00:15:52','1','2026-07-14 00:15:55','1',0),(6,1,'发一个简单的表格内容给我，带表情',1,NULL,NULL,NULL,0.70,4096,77,1,'2026-07-14 11:59:21',1,'2026-07-14 11:59:12','1','2026-07-14 13:54:50','1',1),(7,1,'发一个简单的表格内容给我，带表情',1,NULL,NULL,NULL,0.70,4096,121,1,'2026-07-14 13:55:11',1,'2026-07-14 13:54:58','1','2026-07-14 13:54:58','1',0),(8,1,'发一个简单的表格内容给我',1,NULL,NULL,NULL,0.70,4096,227,2,'2026-07-14 14:25:58',1,'2026-07-14 14:22:43','1','2026-07-14 14:22:43','1',0),(9,1,'查询下浏览量前三的博客文章',1,NULL,NULL,NULL,0.70,4096,317,2,'2026-07-15 13:45:22',1,'2026-07-14 21:58:51','1','2026-07-14 21:58:52','1',0),(10,1,'查询下发布时间最新的三篇文章',1,NULL,NULL,NULL,0.70,4096,230,2,'2026-07-15 13:46:21',1,'2026-07-15 13:45:34','1','2026-07-15 13:45:34','1',0),(11,1,'请记住，我叫张三',1,NULL,NULL,NULL,0.70,4096,52,2,'2026-07-15 15:40:32',1,'2026-07-15 15:40:20','1','2026-07-15 15:40:20','1',0),(12,1,'请记住，我叫张三',1,NULL,NULL,NULL,0.70,4096,231,8,'2026-07-15 22:52:08',1,'2026-07-15 15:53:26','1','2026-07-15 22:52:07','1',0),(13,1,'查询下最近发表的三篇文章',1,NULL,NULL,NULL,0.70,4096,149,1,'2026-07-24 11:35:47',1,'2026-07-24 11:35:27',NULL,'2026-07-24 11:35:48',NULL,0),(14,1,'列出来访问量前五的文章，使用表格给我',1,NULL,NULL,NULL,0.70,4096,236,4,'2026-08-06 09:04:36',1,'2026-07-24 11:42:02',NULL,'2026-08-06 09:04:36',NULL,0),(15,1,'你好，介绍一下你自己',1,NULL,NULL,NULL,0.70,4096,20,1,'2026-08-09 02:16:56',1,'2026-08-09 02:16:51',NULL,'2026-08-09 02:16:58',NULL,0),(16,1,'你好，你是谁？',1,NULL,NULL,NULL,0.70,4096,96,2,'2026-08-09 06:39:31',1,'2026-08-09 02:35:41',NULL,'2026-08-09 06:39:31',NULL,0),(17,1,'dsds',1,NULL,NULL,NULL,0.70,4096,309,5,'2026-08-09 12:58:42',1,'2026-08-09 08:33:24',NULL,'2026-08-20 23:03:38',NULL,1),(18,1,'用一句话介绍自己',1,NULL,NULL,NULL,0.70,4096,12,1,'2026-08-20 23:04:37',1,'2026-08-20 23:04:30',NULL,'2026-08-20 23:04:36',NULL,0),(19,1,'用一句话介绍你自己',1,NULL,NULL,NULL,0.70,4096,14,1,'2026-08-20 23:05:41',1,'2026-08-20 23:05:36',NULL,'2026-08-20 23:05:40',NULL,0),(20,1,'用一句话介绍你自己',1,NULL,NULL,NULL,0.70,4096,66,3,'2026-08-21 00:16:51',1,'2026-08-21 00:12:12',NULL,'2026-08-21 00:16:51',NULL,0);
/*!40000 ALTER TABLE `chat_conversation` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `chat_message`
--

DROP TABLE IF EXISTS `chat_message`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL COMMENT '会话ID',
  `role` int NOT NULL COMMENT '角色(1=用户 2=AI助手 3=系统 4=工具)',
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '消息内容',
  `message_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'text' COMMENT '消息类型(text/image/tool)',
  `tool_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工具名称',
  `tool_payload` json DEFAULT NULL COMMENT '工具调用参数(JSON)',
  `token_count` int NOT NULL DEFAULT '0' COMMENT 'Token数量',
  `status` int NOT NULL DEFAULT '1' COMMENT '状态(1=正常 0=已撤回)',
  `error_message` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '错误信息',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `created_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `updated_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '更新人',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除(0=未删除 1=已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_conversation_id` (`conversation_id`) USING BTREE,
  KEY `idx_role` (`role`) USING BTREE,
  KEY `idx_status` (`status`) USING BTREE,
  KEY `idx_created_at` (`created_at`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=106 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='AI聊天消息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chat_message`
--

LOCK TABLES `chat_message` WRITE;
/*!40000 ALTER TABLE `chat_message` DISABLE KEYS */;
INSERT INTO `chat_message` VALUES (1,1,1,'你好','TEXT',NULL,NULL,0,3,NULL,'2026-07-13 23:54:52','admin','2026-07-13 23:54:52','admin',0),(2,1,2,'你好！很高兴见到你！😊\n\n我可以帮你做很多事情，比如：\n\n- **查找和管理订单**（搜索、创建、更新状态、删除等）\n- **搜索博客文章**（按关键词查找）\n- **查询业务数据**（比如订单统计、文章分析等）\n- **搜索知识库文档**（查找相关资料）\n\n请告诉我你想做什么，我会尽力帮你！','TEXT',NULL,NULL,0,3,NULL,'2026-07-13 23:54:56','admin','2026-07-13 23:54:56','admin',0),(3,2,1,'你是谁？','TEXT',NULL,NULL,0,3,NULL,'2026-07-13 23:56:33','admin','2026-07-13 23:56:33','admin',0),(4,2,2,'你好！我是你的智能助手。  \n\n我可以帮你做很多事情，比如：  \n- **查找信息**：搜索博客文章、订单记录，或者查询知识库文档。  \n- **处理订单**：帮你准备创建、更新或删除订单（操作前会和你确认）。  \n- **分析数据**：通过自然语言查询业务数据，比如订单统计、文章分析等。  \n\n你可以直接告诉我你的需求，我会一步步来协助你。  \n有什么想了解的或者需要我帮忙的吗？','TEXT',NULL,NULL,0,3,NULL,'2026-07-13 23:56:38','admin','2026-07-13 23:56:38','admin',0),(5,2,1,'你发送一个表情给我，用表格','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 00:01:10','admin','2026-07-14 00:01:10','admin',0),(6,2,2,'好的！这是一个用表格展示的表情：\n\n| 😊 |\n|:---:|\n| 比如：Hello！|\n\n或者来个更丰富的：\n\n| 表情 | 含义 |\n|:---:|:---:|\n| 😊 | 开心 |\n| 👋 | 你好 |\n| 🎉 | 庆祝 |\n| 💪 | 加油 |\n| ❤️ | 爱心 |\n| 😄 | 笑脸 |\n\n希望能让你心情愉快！还有什么需要帮忙的吗？ 😄','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 00:01:17','admin','2026-07-14 00:01:17','admin',0),(7,1,1,'你发送一个表情给我，用表格','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 00:04:32','admin','2026-07-14 00:04:32','admin',0),(8,1,2,'好的！这是给你的表情 😊\n\n```\n╔══════════════════════════╗\n║                          ║\n║         😊               ║\n║                          ║\n║      送给你～            ║\n║                          ║\n╚══════════════════════════╝\n```\n\n希望这个表情能让你开心！有什么我可以帮你的吗？','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 00:04:35','admin','2026-07-14 00:04:35','admin',0),(9,3,1,'你发送一个表情给我，用表格','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 00:07:44','admin','2026-07-14 00:07:44','admin',0),(10,3,2,'好的！这是一个用表格做的笑脸表情 😊\n\n```\n╔══════════════════════════════╗\n║                              ║\n║        ██████████████        ║\n║      ██            ██        ║\n║    ██  ●          ●  ██      ║\n║    ██                ██      ║\n║    ██                  ██    ║\n║    ██    ██████████    ██    ║\n║      ██              ██      ║\n║        ██████████████        ║\n║                              ║\n╚══════════════════════════════╝\n```\n\n或者更简洁的版本：\n\n| | | | | |\n|---|---|---|---|---|\n| | ● | | ● | |\n| | | | | |\n| | ╰ | | ╯ | |\n\n😄 希望能让你开心！需要我帮你做其他事情吗？','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 00:07:49','admin','2026-07-14 00:07:49','admin',0),(11,4,1,'发一个简单的表格内容给我','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 00:08:33','admin','2026-07-14 00:08:33','admin',0),(12,4,2,'好的，这是一个简单的表格示例：\n\n| 序号 | 姓名 | 部门 | 职位 |\n|:---:|:---:|:---:|:---:|\n| 1 | 张三 | 技术部 | 工程师 |\n| 2 | 李四 | 市场部 | 经理 |\n| 3 | 王五 | 财务部 | 会计 |\n| 4 | 赵六 | 人事部 | 主管 |\n\n---\n\n如果您需要其他格式或内容的表格，请告诉我！比如：\n- Markdown 格式\n- HTML 格式\n- CSV 格式\n- 或特定主题的数据表格','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 00:08:35','admin','2026-07-14 00:08:35','admin',0),(13,2,1,'发一个简单的表格内容给我 3*3的','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 00:13:01','admin','2026-07-14 00:13:01','admin',0),(14,2,2,'好的！这是一个简单的 3×3 表格：\n\n| A | B | C |\n|:---:|:---:|:---:|\n| 1 | 2 | 3 |\n| 4 | 5 | 6 |\n| 7 | 8 | 9 |\n\n或者带点内容的：\n\n| 水果 | 颜色 | 价格 |\n|:---:|:---:|:---:|\n| 苹果 | 红色 | 5元 |\n| 香蕉 | 黄色 | 3元 |\n| 葡萄 | 紫色 | 8元 |\n\n还需要其他表格吗？😊','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 00:13:04','admin','2026-07-14 00:13:04','admin',0),(15,5,1,'你发送一个表情给我，用表格','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 00:15:52','admin','2026-07-14 00:15:52','admin',0),(16,5,2,'以下是为你准备的表情表格：\n\n| 表情 | 名称 | 含义 |\n|:---:|:---:|:---|\n| 😊 | 微笑 | 友好、开心 |\n| 😂 | 笑哭 | 太好笑了、哭笑不得 |\n| ❤️ | 红心 | 喜爱、爱、喜欢 |\n| 👍 | 点赞 | 赞同、优秀、支持 |\n| 🌟 | 星星 | 闪耀、特别、优秀 |\n\n你可以选择一个喜欢的表情，我会为你重新生成对应表格。','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 00:15:56','admin','2026-07-14 00:15:56','admin',0),(17,5,1,'你发送一个表情给我，用表格','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 11:36:01','admin','2026-07-14 11:36:01','admin',0),(18,2,1,'发一个简单的表格内容给我','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 11:43:41',NULL,'2026-07-14 11:43:41',NULL,0),(19,2,2,'| 项目 | 数量 | 价格 |\n|:---:|:---:|:---:|\n| 苹果 | 5 | ¥10 |\n| 香蕉 | 3 | ¥6 |\n| 橙子 | 4 | ¥8 |','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 11:43:48',NULL,'2026-07-14 11:43:48',NULL,0),(20,2,1,'发一个简单的表格内容给我，带表情','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 11:47:37',NULL,'2026-07-14 11:47:37',NULL,0),(21,2,2,'| 🌤️ | 天气 | 心情 |\n|:---:|:---:|:---:|\n| ☀️ | 晴天 | 😊 开心 |\n| 🌧️ | 雨天 | 😢 低落 |\n| ☁️ | 多云 | 😐 平静 |\n\n---\n\n| 待办事项 | 状态 |\n|:---|:---:|\n| 📝 写报告 | ⏳ 进行中 |\n| 🛒 买菜 | ✅ 完成 |\n| 📞 打电话 | ❌ 未开始 |\n\n还需要其他表格吗？😊','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 11:47:46',NULL,'2026-07-14 11:47:46',NULL,0),(22,1,1,'发一个简单的表格内容给我，带表情','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 11:58:35',NULL,'2026-07-14 11:58:35',NULL,0),(23,1,2,'收到！这是一个很好的表格示例。\n\n你是想让我基于这个格式做些什么，还是需要我帮你处理其他任务呢？','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 11:58:43',NULL,'2026-07-14 11:58:43',NULL,0),(26,7,1,'发一个简单的表格内容给我，带表情','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 13:54:58',NULL,'2026-07-14 13:54:58',NULL,0),(27,7,2,'这里有一些简单的带表情表格示例供你参考：\n\n## 📅 日程表\n| 时间 | 事项 | 状态 |\n|------|------|------|\n| 09:00 | ☀️ 早会 | ✅ 完成 |\n| 14:00 | 📧 处理邮件 | 🔄 进行中 |\n| 16:00 | 💼 客户会议 | ⏳ 待开始 |\n\n## 🍎 水果清单\n| 水果 | 数量 | 价格 |\n|------|------|------|\n| 🍎 苹果 | 5个 | ¥15 |\n| 🍌 香蕉 | 1串 | ¥8 |\n| 🍊 橙子 | 3个 | ¥12 |\n\n## 💼 工作任务\n| 任务 | 负责人 | 状态 |\n|------|--------|------|\n| 📝 撰写报告 | 张三 | ✅ 已完成 |\n| 💻 代码开发 | 李四 | 🔄 进行中 |\n| 🎨 设计审核 | 王五 | ⏳ 待开始 |\n\n---\n\n😊 希望这些示例对你有帮助！如果你需要特定内容的表格，比如**订单数据**、**业务统计**或**博客文章列表**等，我可以帮你从系统中获取真实数据来生成表格。','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 13:55:11',NULL,'2026-07-14 13:55:11',NULL,0),(28,8,1,'发一个简单的表格内容给我','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 14:22:43',NULL,'2026-07-14 14:22:43',NULL,0),(29,8,2,'以下是最近5篇博客文章的表格内容：\n\n| 序号 | 文章标题 | 浏览量 | 发布时间 |\n|------|----------|--------|----------|\n| 1 | Spring Boot 3.x 新特性详解 | 1,280 | 2026-07-01 |\n| 2 | Vue 3 Composition API 最佳实践 | 956 | 2026-06-28 |\n| 3 | Docker 容器化部署实战指南 | 1,567 | 2026-06-25 |\n| 4 | MySQL 8.0 窗口函数完全指南 | 856 | 2026-06-20 |\n| 5 | Redis 缓存策略与最佳实践 | 1,120 | 2026-06-18 |\n\n**数据概览：**\n- 总浏览量：5,779次\n- 最高浏览量：《Docker 容器化部署实战指南》（1,567次）\n- 最低浏览量：《MySQL 8.0 窗口函数完全指南》（856次）\n\n您需要其他类型的表格吗？比如订单数据、用户数据，或者有特定格式要求的表格？','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 14:23:07',NULL,'2026-07-14 14:23:07',NULL,0),(30,8,1,'发一个简单的表格内容给我，带表情','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 14:25:42',NULL,'2026-07-14 14:25:42',NULL,0),(31,8,2,'# 📋 待办事项清单\n\n| 序号 | 任务内容 | 状态 | 优先级 | 截止日期 |\n|------|----------|------|--------|----------|\n| 1 | 📝 撰写项目报告 | ✅ 已完成 | 🔴 高 | 2026-07-15 |\n| 2 | 📞 联系客户 | ⏳ 进行中 | 🟡 中 | 2026-07-20 |\n| 3 | 🛠️ 修复Bug | 📌 待处理 | 🔴 高 | 2026-07-18 |\n| 4 | 📊 整理数据 | 📌 待处理 | 🟢 低 | 2026-07-25 |\n| 5 | 📚 学习新技术 | 🎯 计划中 | 🟢 低 | 2026-07-30 |\n\n---\n\n**状态说明：**\n- ✅ 已完成\n- ⏳ 进行中  \n- 📌 待处理\n- 🎯 计划中\n\n**优先级说明：**\n- 🔴 高优先级\n- 🟡 中优先级\n- 🟢 低优先级\n\n需要我创建其他类型的表格吗？比如订单表格、产品表格或者数据统计表格？','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 14:25:58',NULL,'2026-07-14 14:25:58',NULL,0),(32,8,1,'查询下浏览量前三的博客文章','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 16:42:58',NULL,'2026-07-14 16:42:58',NULL,0),(33,9,1,'查询下浏览量前三的博客文章','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 21:58:51',NULL,'2026-07-14 21:58:51',NULL,0),(34,9,2,'根据查询结果，以下是**浏览量最高的三篇博客文章**：\n\n| 排名 | 文章标题 | 浏览量 | 点赞数 | 评论数 | 发布时间 |\n|------|----------|--------|--------|--------|----------|\n| 🥇 第1名 | **Spring AI 2.0：构建企业级 Agent 编排系统** | **2,156** | 78 | 25 | 2026-06-12 |\n| 🥈 第2名 | **AI Agent 开发入门指南** | **1,890** | 62 | 20 | 2026-06-05 |\n| 🥉 第3名 | **Docker 容器化部署实战指南** | **1,567** | 56 | 18 | 2026-06-25 |\n\n### 📊 数据洞察\n\n- **AI Agent 相关主题热度高**：前两篇都是 AI Agent 相关内容，说明这个主题很受欢迎\n- **互动率分析**：第一篇文章的点赞和评论数也是最高的，内容质量得到读者认可\n- **发布日期**：浏览量最高的文章并非最新发布，说明优质内容具有长尾效应\n\n如果您想了解其他文章数据，比如点赞最高、评论最多的文章，我可以继续帮您查询！','TEXT',NULL,NULL,0,3,NULL,'2026-07-14 21:59:09',NULL,'2026-07-14 21:59:09',NULL,0),(35,9,1,'查询下发布时间最新的三篇文章','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 13:45:04',NULL,'2026-07-15 13:45:04',NULL,0),(36,9,2,'根据查询结果，以下是**发布时间最新的三篇文章**（按从新到旧排列）：\n\n| 排名 | 文章标题 | 发布时间 | 摘要 | 浏览量 | 点赞数 | 评论数 |\n|------|----------|----------|------|--------|--------|--------|\n| 🥇 第1名 | **Spring Boot 3.x 新特性详解** | 2026年7月1日 10:00 | Spring Boot 3.x 重要版本升级，支持 Java 17、Jakarta EE 9+、GraalVM 原生编译等新特性 | 1,280 | 45 | 12 |\n| 🥈 第2名 | **Vue 3 Composition API 最佳实践** | 2026年6月28日 14:00 | Vue 3 Composition API 最佳实践，包括组合式函数、响应式工具等实用模式 | 956 | 32 | 8 |\n| 🥉 第3名 | **Docker 容器化部署实战指南** | 2026年6月25日 09:00 | Docker 容器化部署实战，包括 Dockerfile 编写、多阶段构建、Docker Compose 编排等 | 1,567 | 56 | 18 |\n\n### 📊 内容分析\n\n1. **技术主题为主**：三篇都是技术类文章，涵盖后端框架、前端框架和容器化技术\n2. **时间分布**：最近三篇文章发布在2026年6月25日至7月1日期间，约一周时间发布3篇\n3. **互动情况**：Docker文章虽然发布时间较早，但浏览量和互动数据都最好，说明实用性强的内容更受欢迎\n\n如果您想了解更多文章数据，或者有其他查询需求，请随时告诉我！','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 13:45:22',NULL,'2026-07-15 13:45:22',NULL,0),(37,10,1,'查询下发布时间最新的三篇文章','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 13:45:34',NULL,'2026-07-15 13:45:34',NULL,0),(38,10,2,'根据您的需求，我通过业务数据查询工具获取了最新的三篇文章信息。这些文章按发布时间倒序排列，包含了标题、摘要、发布日期、互动数据等完整信息。','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 13:45:49',NULL,'2026-07-15 13:45:49',NULL,0),(39,10,1,'使用表格给我展示出来','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 13:46:07',NULL,'2026-07-15 13:46:07',NULL,0),(40,10,2,'已为您查询到最新的三篇文章，以下是表格展示：\n\n## 最新发布的三篇文章\n\n| 序号 | 标题 | 摘要 | 发布时间 | 浏览量 | 点赞数 | 评论数 | 阅读时间 |\n|:---:|------|------|:--------:|:------:|:------:|:------:|:--------:|\n| 1 | Spring Boot 3.x 新特性详解 | Spring Boot 3.x 重要版本升级，支持 Java 17、Jakarta EE 9+、GraalVM 原生编译等新特性 | 2026-07-01 10:00 | 1,280 | 45 | 12 | 12分钟 |\n| 2 | Vue 3 Composition API 最佳实践 | Vue 3 Composition API 最佳实践，包括组合式函数、响应式工具等实用模式 | 2026-06-28 14:00 | 956 | 32 | 8 | 10分钟 |\n| 3 | Docker 容器化部署实战指南 | Docker 容器化部署实战，包括 Dockerfile 编写、多阶段构建、Docker Compose 编排等 | 2026-06-25 09:00 | 1,567 | 56 | 18 | 15分钟 |\n\n## 文章概览\n\n| 统计维度 | 数值 |\n|:--------:|:----:|\n| 总文章数 | 3 篇 |\n| 总浏览量 | 3,803 |\n| 总点赞数 | 133 |\n| 总评论数 | 38 |\n| 平均阅读时长 | 12.3 分钟 |\n\n## 文章特点\n\n- **Spring Boot 3.x 新特性详解** - 技术深度最高，Java 后端开发者必读\n- **Vue 3 Composition API 最佳实践** - 前端开发实用指南，互动率良好\n- **Docker 容器化部署实战指南** - 浏览量最高，运维部署热门话题\n\n如需查看某篇文章的详细内容，请告诉我文章标题或序号。','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 13:46:21',NULL,'2026-07-15 13:46:21',NULL,0),(41,11,1,'请记住，我叫张三','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 15:40:20',NULL,'2026-07-15 15:40:20',NULL,0),(42,11,2,'您好，张三！关于订单，您具体想要做什么呢？比如：\n\n1. **创建新订单** - 需要商品标题、金额和客户名称\n2. **查询订单** - 可以按关键词、日期范围或状态（paid/unpaid/completed）筛选\n3. **更新订单状态** - 需要订单ID和目标状态（paid/completed）\n4. **删除订单** - 需要订单ID\n\n请告诉我您的具体需求，我会帮您处理！','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 15:40:28',NULL,'2026-07-15 15:40:28',NULL,0),(43,11,1,'请问我的名字是什么？','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 15:40:29',NULL,'2026-07-15 15:40:29',NULL,0),(44,11,2,'您之前告诉我，您叫**张三**。','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 15:40:32',NULL,'2026-07-15 15:40:32',NULL,0),(45,11,1,'查询下发布时间最新的三篇文章','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 15:40:34',NULL,'2026-07-15 15:40:34',NULL,0),(46,12,1,'请记住，我叫张三','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 15:53:26',NULL,'2026-07-15 15:53:26',NULL,0),(47,12,2,'好的，张三，我已经记住了您的名字。请问有什么我可以帮助您的？','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 15:53:30',NULL,'2026-07-15 15:53:30',NULL,0),(48,12,1,'请问我的名字是什么？','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 15:53:42',NULL,'2026-07-15 15:53:42',NULL,0),(49,12,1,'请问我的名字是什么？','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 15:54:01',NULL,'2026-07-15 15:54:01',NULL,0),(50,12,2,'您的名字是张三。','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 15:54:05',NULL,'2026-07-15 15:54:05',NULL,0),(51,12,1,'查询下发布时间最新的三篇文章','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 15:54:09',NULL,'2026-07-15 15:54:09',NULL,0),(52,12,2,'您的名字是张三。','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 15:54:27',NULL,'2026-07-15 15:54:27',NULL,0),(53,12,1,'查询下发布时间最新的三篇文章','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 15:54:49',NULL,'2026-07-15 15:54:49',NULL,0),(54,12,1,'请问我的名字是什么？','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 16:50:04',NULL,'2026-07-15 16:50:04',NULL,0),(55,12,2,'张三，您的名字是张三。\n\n关于最新的三篇文章，查询结果如下：\n\n1. **Spring Boot 3.x 新特性详解**  \n   - 发布时间：2026-07-01  \n   - 摘要：Spring Boot 3.x 重要版本升级，支持 Java 17、Jakarta EE 9+、GraalVM 原生编译等新特性。\n\n2. **Vue 3 Composition API 最佳实践**  \n   - 发布时间：2026-06-28  \n   - 摘要：Vue 3 Composition API 最佳实践，包括组合式函数、响应式工具等实用模式。\n\n3. **Docker 容器化部署实战指南**  \n   - 发布时间：2026-06-25  \n   - 摘要：Docker 容器化部署实战，包括 Dockerfile 编写、多阶段构建、Docker Compose 编排等。\n\n这三篇文章是按照发布时间从新到旧排序的。如果您需要查看更多文章或了解其他信息，请告诉我。','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 16:50:21',NULL,'2026-07-15 16:50:21',NULL,0),(56,12,1,'我的名字叫啥？直说名字','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 16:50:32',NULL,'2026-07-15 16:50:32',NULL,0),(57,12,2,'张三','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 16:50:35',NULL,'2026-07-15 16:50:35',NULL,0),(58,12,1,'查询下访问量前2的文档，表格形式给我','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 16:51:00',NULL,'2026-07-15 16:51:00',NULL,0),(59,12,2,'| 标题 | 访问量 |\n|------|--------|\n| Spring Boot 3.x 新特性详解 | 1 |\n| Spring AI 2.0：构建企业级 Agent 编排系统 | 1 |\n\n这两篇文章的访问量都是1。','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 16:51:10',NULL,'2026-07-15 16:51:10',NULL,0),(60,12,1,'统计下 每个文档的访问量','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 19:17:52',NULL,'2026-07-15 19:17:52',NULL,0),(61,12,2,'| 标题 | 访问量 |\n|------|--------|\n| Spring Boot 3.x 新特性详解 | 1 |\n| Spring AI 2.0：构建企业级 Agent 编排系统 | 1 |\n| Vue 3 Composition API 最佳实践 | 0 |\n| Docker 容器化部署实战指南 | 0 |\n| MySQL 8.0 窗口函数完全指南 | 0 |\n| Redis 缓存策略与最佳实践 | 0 |\n| TypeScript 高级类型技巧 | 0 |\n| Git 工作流最佳实践 | 0 |\n| Nginx 反向代理与负载均衡 | 0 |\n| AI Agent 开发入门指南 | 0 |\n\n共有10篇文档，其中2篇访问量为1，8篇访问量为0。','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 19:18:05',NULL,'2026-07-15 19:18:05',NULL,0),(62,12,1,'我是谁？','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 22:52:02',NULL,'2026-07-15 22:52:02',NULL,0),(63,12,2,'张三','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 22:52:08',NULL,'2026-07-15 22:52:08',NULL,0),(64,3,1,'给我用表情花一个大大的爱心','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 23:02:35',NULL,'2026-07-15 23:02:35',NULL,0),(65,3,2,'好的！送你一个大大的爱心 ❤️💕\n\n```\n        💕💕              💕💕\n      💖💖💖💖          💖💖💖💖\n    💗💗💗💗💗💗      💗💗💗💗💗💗\n  ❤️❤️❤️❤️❤️❤️❤️  ❤️❤️❤️❤️❤️❤️❤️\n  💕💕💕💕💕💕💕💕💕💕💕💕💕💕💕\n    💖💖💖💖💖💖💖💖💖💖💖💖💖\n      💗💗💗💗💗💗💗💗💗💗💗\n        💕💕💕💕💕💕💕💕💕\n          ❤️❤️❤️❤️❤️❤️❤️\n            💖💖💖💖💖\n              💗💗💗\n                💕\n```\n\n或者更精致版本：\n\n```\n    ✨💗✨       ✨💗✨\n  💕💕💕💕💕   💕💕💕💕💕\n💖❤️💖❤️💖❤️💖❤️💖❤️💖❤️💖\n💕❤️💕❤️💕❤️💕❤️💕❤️💕❤️💕\n💖❤️💖❤️💖❤️💖❤️💖❤️💖❤️💖\n  💕❤️💕❤️💕❤️💕❤️💕❤️💕\n    💖❤️💖❤️💖❤️💖❤️💖\n      💕❤️💕❤️💕❤️💕\n        💖❤️💖❤️💖\n          💕❤️💕\n            💖\n```\n\n送给你！希望你喜欢 💖✨','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 23:02:52',NULL,'2026-07-15 23:02:52',NULL,0),(66,3,1,'画一个 一箭穿心那种 图案','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 23:03:15',NULL,'2026-07-15 23:03:15',NULL,0),(67,3,2,'```\n                    🏹\n                     \\\n                      \\\n                       ❤️\n                      /\n                     /\n                    🏹\n```\n\n或者更精致的版本：\n\n```\n              →→→→→→→→→\n              ↓         ↓\n              ↓   ❤️❤️   ↓\n              ↓  ❤️❤️❤️  ↓\n              ↓ ❤️❤️❤️❤️ ↓\n              ↓→❤️❤️❤️❤️→↓\n              ↓ ❤️❤️❤️❤️ ↓\n              ↓  ❤️❤️❤️  ↓\n              ↓   ❤️❤️   ↓\n              ↓         ↓\n              →→→→→→→→→\n```\n\n再送你一个用表情符号的浪漫版本：\n\n```\n  💘 → → → → → → 💘\n       💕💕💕💕\n     💕💕💕💕💕💕\n    💕💕💕💕💕💕💕\n    💕💕💕💕💕💕💕💕\n     💕💕💕💕💕💕💕\n       💕💕💕💕💕\n         💕💕💕\n```\n\n还有最经典的表达：\n\n# 💘🏹💕\n\n**一箭穿心，寓意着：**\n- 🏹 爱神丘比特之箭\n- ❤️ 被爱击中的心\n- 💕 浪漫与爱情\n\n希望你喜欢！需要其他图案吗？ 😊','TEXT',NULL,NULL,0,3,NULL,'2026-07-15 23:03:52',NULL,'2026-07-15 23:03:52',NULL,0),(68,13,1,'查询下最近发表的三篇文章','TEXT',NULL,NULL,0,3,NULL,'2026-07-24 11:35:26',NULL,'2026-07-24 11:35:26',NULL,0),(69,13,2,'以下是最近发表的三篇文章：\n\n## 1. Spring Boot 3.x 新特性详解\n- **发布时间**：2026-07-01 10:00\n- **摘要**：Spring Boot 3.x 重要版本升级，支持 Java 17、Jakarta EE 9+、GraalVM 原生编译等新特性。\n- **阅读量**：14 | **点赞数**：45 | **评论数**：12\n- **状态**：✅ 已置顶 | ⭐ 已推荐\n\n## 2. Vue 3 Composition API 最佳实践\n- **发布时间**：2026-06-28 14:00\n- **摘要**：Vue 3 Composition API 最佳实践，包括组合式函数、响应式工具等实用模式。\n- **阅读量**：8 | **点赞数**：32 | **评论数**：8\n- **状态**：⭐ 已推荐\n\n## 3. Docker 容器化部署实战指南\n- **发布时间**：2026-06-25 09:00\n- **摘要**：Docker 容器化部署实战，包括 Dockerfile 编写、多阶段构建、Docker Compose 编排等。\n- **阅读量**：3 | **点赞数**：56 | **评论数**：18\n- **状态**：普通文章\n\n这三篇文章都处于已发布状态，涵盖了Spring Boot、Vue 3和Docker三个技术领域。','TEXT',NULL,NULL,0,3,NULL,'2026-07-24 11:35:47',NULL,'2026-07-24 11:35:47',NULL,0),(70,14,1,'列出来访问量前五的文章，使用表格给我','TEXT',NULL,NULL,0,3,NULL,'2026-07-24 11:42:01',NULL,'2026-07-24 11:42:01',NULL,0),(71,14,2,'以下是访问量前五的文章：\n\n| 排名 | 文章标题 | 访问量 |\n|:---:|---------|:------:|\n| 1 | Spring Boot 3.x 新特性详解 | 14 |\n| 2 | Spring AI 2.0：构建企业级 Agent 编排系统 | 9 |\n| 3 | Vue 3 Composition API 最佳实践 | 8 |\n| 4 | Docker 容器化部署实战指南 | 3 |\n| 5 | MySQL 8.0 窗口函数完全指南 | 0 |\n\n📊 **简要分析：**\n- 🥇 **Spring Boot 3.x 新特性详解** 遥遥领先，访问量最高\n- Spring 相关内容表现突出，占据前两名\n- 第5名文章目前访问量为0，可能刚发布不久','TEXT',NULL,NULL,0,3,NULL,'2026-07-24 11:42:16',NULL,'2026-07-24 11:42:16',NULL,0),(72,14,1,'我是谁','TEXT',NULL,NULL,0,3,NULL,'2026-07-31 23:30:29',NULL,'2026-07-31 23:30:29',NULL,0),(73,14,2,'根据系统记录，您的身份信息如下：\n\n| 项目 | 信息 |\n|:---:|------|\n| **用户ID** | user_0043 |\n| **姓名** | 于涛 |\n| **角色** | 编辑（editor） |\n\n您是本系统的**编辑**，可以进行博客文章和订单的管理操作。有什么我可以帮您的吗？','TEXT',NULL,NULL,0,3,NULL,'2026-07-31 23:30:41',NULL,'2026-07-31 23:30:41',NULL,0),(74,14,1,'我是谁','TEXT',NULL,NULL,0,3,NULL,'2026-08-06 09:03:52',NULL,'2026-08-06 09:03:52',NULL,0),(75,14,2,'根据当前登录信息，您是：\n\n| 项目 | 信息 |\n|:---:|------|\n| **用户ID** | user_0043 |\n| **姓名** | 于涛 |\n| **角色** | 编辑（editor） |\n\n您是系统的**编辑**，拥有博客文章和订单的管理权限。有什么需要我帮忙的吗？','TEXT',NULL,NULL,0,3,NULL,'2026-08-06 09:04:05',NULL,'2026-08-06 09:04:05',NULL,0),(76,14,1,'列出来访问量前3的文章','TEXT',NULL,NULL,0,3,NULL,'2026-08-06 09:04:19',NULL,'2026-08-06 09:04:19',NULL,0),(77,14,2,'以下是访问量前3的文章：\n\n| 排名 | 文章标题 | 访问量 |\n|:---:|---------|:------:|\n| 1 | Spring Boot 3.x 新特性详解 | 20 |\n| 2 | Spring AI 2.0：构建企业级 Agent 编排系统 | 17 |\n| 3 | Vue 3 Composition API 最佳实践 | 8 |\n\n📊 **简要分析：**\n- 🥇 **Spring Boot 3.x 新特性详解** 位居榜首\n- 🥈 **Spring AI 2.0** 位列第二，AI 相关内容表现强劲\n- 🥉 **Vue 3 Composition API** 是前端领域的热门文章','TEXT',NULL,NULL,0,3,NULL,'2026-08-06 09:04:36',NULL,'2026-08-06 09:04:36',NULL,0),(78,1,1,'列出来访问量前3的文章','TEXT',NULL,NULL,0,3,NULL,'2026-08-06 09:04:51',NULL,'2026-08-06 09:04:51',NULL,0),(79,1,2,'这是访问量前三的文章：\n\n```\n🏆 访问量 TOP 3 文章\n┌────┬────────────────────────────────────────┬──────┐\n│ 排名 │ 文章标题                               │ 访问量 │\n├────┼────────────────────────────────────────┼──────┤\n│ 🥇 │ Spring Boot 3.x 新特性详解              │  20   │\n│ 🥈 │ Spring AI 2.0：构建企业级 Agent 编排系统 │  17   │\n│ 🥉 │ Vue 3 Composition API 最佳实践          │  10   │\n└────┴────────────────────────────────────────┴──────┘\n```','TEXT',NULL,NULL,0,3,NULL,'2026-08-06 09:05:07',NULL,'2026-08-06 09:05:07',NULL,0),(80,15,1,'你好，介绍一下你自己','TEXT',NULL,NULL,0,3,NULL,'2026-08-09 02:16:48',NULL,'2026-08-09 02:16:48',NULL,0),(81,15,2,'你好！我是一个AI助手，主要功能是帮你搜索和整理博客文章，回答你可能感兴趣的内容。如果你有任何问题，或者想找某类文章，随时告诉我，我会尽力提供清晰、有用的信息 😊','TEXT',NULL,NULL,0,3,NULL,'2026-08-09 02:16:55',NULL,'2026-08-09 02:16:55',NULL,0),(82,16,1,'你好，你是谁？','TEXT',NULL,NULL,0,3,NULL,'2026-08-09 02:35:38',NULL,'2026-08-09 02:35:38',NULL,0),(83,16,2,'你好！我是 **HuiSpace 智能助手**，一个专门为 Java 全栈与 AI 应用开发者（H）的作品集网站设计的智能问答助手。\n\n我可以帮助你：\n- 了解网站内容和技术栈\n- 回答关于开发者作品集的问题\n- 提供技术相关的建议和指导\n\n有什么我可以帮你的吗？ 😊','TEXT',NULL,NULL,0,3,NULL,'2026-08-09 02:35:55',NULL,'2026-08-09 02:35:55',NULL,0),(84,16,1,'你好 东莞今天八月九日天日如何 你知道吗？','TEXT',NULL,NULL,0,3,NULL,'2026-08-09 06:39:23',NULL,'2026-08-09 06:39:23',NULL,0),(85,16,2,'你好！很抱歉，我目前没有查询实时天气数据的能力。我主要是为 Java 全栈与 AI 应用开发者（HuiSpace）提供技术支持和作品集展示的助手。\n\n要查询东莞今天（8月9日）的天气，我建议你可以：\n\n1. **使用天气APP**：如墨迹天气、彩云天气、手机自带天气应用等\n2. **访问天气网站**：如中国天气网（weather.com.cn）、百度搜索\"东莞天气\"\n3. **直接询问语音助手**：如 Siri、小爱同学等\n\n如果你有关于技术开发、作品集设计或其他我能帮助的问题，我很乐意为你解答！ 😊','TEXT',NULL,NULL,0,3,NULL,'2026-08-09 06:39:31',NULL,'2026-08-09 06:39:31',NULL,0),(96,18,1,'用一句话介绍自己','TEXT',NULL,NULL,0,3,NULL,'2026-08-20 23:04:31',NULL,'2026-08-20 23:04:31',NULL,0),(97,18,2,'我是 HuiSpace 智能助手，能回答专业问题、识别图片内容、查询业务数据，帮你轻松解决问题。','TEXT',NULL,NULL,0,3,NULL,'2026-08-20 23:04:37',NULL,'2026-08-20 23:04:37',NULL,0),(98,19,1,'用一句话介绍你自己','TEXT',NULL,NULL,0,3,NULL,'2026-08-20 23:05:36',NULL,'2026-08-20 23:05:36',NULL,0),(99,19,2,'我是 HuiSpace 智能助手，能够帮助您搜索知识库文档、查询业务数据、分析图片内容，为您提供高效便捷的智能服务。','TEXT',NULL,NULL,0,3,NULL,'2026-08-20 23:05:41',NULL,'2026-08-20 23:05:41',NULL,0),(100,20,1,'用一句话介绍你自己','TEXT',NULL,NULL,0,3,NULL,'2026-08-21 00:12:13',NULL,'2026-08-21 00:12:13',NULL,0),(101,20,2,'我是 HuiSpace 智能助手，擅长通过 RAG 检索、业务数据查询和视觉理解来帮助您高效获取信息与洞察。','TEXT',NULL,NULL,0,3,NULL,'2026-08-21 00:12:26',NULL,'2026-08-21 00:12:26',NULL,0),(102,20,1,'你得底层是什么大模型？','TEXT',NULL,NULL,0,3,NULL,'2026-08-21 00:16:23',NULL,'2026-08-21 00:16:23',NULL,0),(103,20,2,'我是 HuiSpace 智能助手，基于大语言模型技术构建。关于具体的底层模型型号和版本等技术细节，属于产品的技术架构信息，我无法提供详细的内部配置信息。如果您有技术合作或深入了解的需求，建议联系我们的技术团队获取更多资讯。','TEXT',NULL,NULL,0,3,NULL,'2026-08-21 00:16:31',NULL,'2026-08-21 00:16:31',NULL,0),(104,20,1,'你得底层是什么大模型？我说的是大模型','TEXT',NULL,NULL,0,3,NULL,'2026-08-21 00:16:40',NULL,'2026-08-21 00:16:40',NULL,0),(105,20,2,'我目前无法提供关于底层大模型的具体型号信息，因为这涉及产品的技术架构细节。不过，我可以告诉您我支持搜索知识库、查询业务数据以及图像识别等功能，可以帮助您解决各种问题。如果您有其他问题或需要帮助，请随时告诉我！','TEXT',NULL,NULL,0,3,NULL,'2026-08-21 00:16:51',NULL,'2026-08-21 00:16:51',NULL,0);
/*!40000 ALTER TABLE `chat_message` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `chat_pending_action`
--

DROP TABLE IF EXISTS `chat_pending_action`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_pending_action` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '操作ID',
  `conversation_id` bigint NOT NULL COMMENT '会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `action_type` int NOT NULL COMMENT '操作类型(1=创建订单 2=修改订单 3=删除订单 4=查询数据)',
  `target_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '目标类型',
  `target_id` bigint DEFAULT NULL COMMENT '目标ID',
  `payload_json` json DEFAULT NULL COMMENT '操作参数(JSON)',
  `preview_text` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '预览文本',
  `status` int NOT NULL DEFAULT '1' COMMENT '状态(1=待确认 2=已确认 3=已取消 4=已执行 5=已过期)',
  `expire_time` datetime NOT NULL COMMENT '过期时间',
  `confirmed_at` datetime DEFAULT NULL COMMENT '确认时间',
  `executed_at` datetime DEFAULT NULL COMMENT '执行时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `created_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `updated_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '更新人',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除(0=未删除 1=已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_conversation_id` (`conversation_id`) USING BTREE,
  KEY `idx_user_id` (`user_id`) USING BTREE,
  KEY `idx_status` (`status`) USING BTREE,
  KEY `idx_expire_time` (`expire_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='AI待确认操作表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chat_pending_action`
--

LOCK TABLES `chat_pending_action` WRITE;
/*!40000 ALTER TABLE `chat_pending_action` DISABLE KEYS */;
/*!40000 ALTER TABLE `chat_pending_action` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `hagent_user`
--

DROP TABLE IF EXISTS `hagent_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `hagent_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '登录用户名',
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '登录密码(BCrypt哈希)',
  `nickname` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '昵称',
  `avatar` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '头像URL',
  `role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'admin' COMMENT '角色(默认admin，多角色可用逗号分隔)',
  `status` int NOT NULL DEFAULT '1' COMMENT '状态(1=正常 0=禁用)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除(0=未删除 1=已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_username` (`username`) USING BTREE,
  KEY `idx_status` (`status`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='Sa-Token登录用户表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `hagent_user`
--

LOCK TABLES `hagent_user` WRITE;
/*!40000 ALTER TABLE `hagent_user` DISABLE KEYS */;
INSERT INTO `hagent_user` VALUES (1,'admin','$2a$10$eZ2hjlch.kJMUk.96RTJ6Ormwhx.C8Ryw3MKerU.RodxPhtCoNkFa','系统管理员',NULL,'admin',1,'2026-08-20 22:13:29','2026-08-20 22:26:53',0);
/*!40000 ALTER TABLE `hagent_user` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `knowledge_file`
--

DROP TABLE IF EXISTS `knowledge_file`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `knowledge_file` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `file_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件名',
  `file_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件URL',
  `object_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '对象存储路径',
  `file_size` bigint NOT NULL DEFAULT '0' COMMENT '文件大小(字节)',
  `file_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件类型(pdf/docx/txt/md)',
  `file_ext` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件扩展名',
  `vector_status` int NOT NULL DEFAULT '1' COMMENT '向量化状态(1=待处理 2=处理中 3=已完成 4=失败)',
  `vector_version` int NOT NULL DEFAULT '1' COMMENT '向量版本号',
  `chunk_count` int NOT NULL DEFAULT '0' COMMENT '分块数量',
  `token_count` int NOT NULL DEFAULT '0' COMMENT 'Token数量',
  `error_message` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '错误信息',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `created_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `updated_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '更新人',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除(0=未删除 1=已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_vector_status` (`vector_status`) USING BTREE,
  KEY `idx_file_type` (`file_type`) USING BTREE,
  KEY `idx_created_at` (`created_at`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2075228410665365507 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='知识库文件表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `knowledge_file`
--

LOCK TABLES `knowledge_file` WRITE;
/*!40000 ALTER TABLE `knowledge_file` DISABLE KEYS */;
INSERT INTO `knowledge_file` VALUES (2075199572472258562,'test.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783601107862_test.txt','knowledge/1783601107862_test.txt',13,'text/plain','txt',3,1,1,3,NULL,'2026-07-09 20:45:08',NULL,'2026-07-09 20:52:01',NULL,0),(2075201765099286530,'auto-vector-test.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783601630659_auto-vector-test.txt','knowledge/1783601630659_auto-vector-test.txt',55,'text/plain','txt',3,1,1,6,NULL,'2026-07-09 20:53:51',NULL,'2026-07-09 20:53:52',NULL,0),(2075202070251679745,'search-test.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783601703462_search-test.txt','knowledge/1783601703462_search-test.txt',101,'text/plain','txt',3,1,1,11,NULL,'2026-07-09 20:55:04',NULL,'2026-07-09 20:55:04',NULL,0),(2075203536588759041,'test-vector.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783602052975_test-vector.txt','knowledge/1783602052975_test-vector.txt',118,'text/plain','txt',3,1,1,12,NULL,'2026-07-09 21:00:53',NULL,'2026-07-09 21:00:54',NULL,0),(2075205939098587137,'debug-test.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783602625703_debug-test.txt','knowledge/1783602625703_debug-test.txt',85,'text/plain','txt',3,1,1,9,NULL,'2026-07-09 21:10:26',NULL,'2026-07-09 21:10:27',NULL,0),(2075206814428946434,'test-final.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783602834492_test-final.txt','knowledge/1783602834492_test-final.txt',127,'text/plain','txt',3,1,1,13,NULL,'2026-07-09 21:13:55',NULL,'2026-07-09 21:13:56',NULL,0),(2075208126008676354,'debug-vector.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783603147163_debug-vector.txt','knowledge/1783603147163_debug-vector.txt',37,'text/plain','txt',3,1,1,3,NULL,'2026-07-09 21:19:07',NULL,'2026-07-09 21:19:08',NULL,0),(2075209553200304130,'test-new-index.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783603487463_test-new-index.txt','knowledge/1783603487463_test-new-index.txt',70,'text/plain','txt',3,1,1,6,NULL,'2026-07-09 21:24:48',NULL,'2026-07-09 21:24:49',NULL,0),(2075211051560894466,'debug-embedding.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783603844699_debug-embedding.txt','knowledge/1783603844699_debug-embedding.txt',37,'text/plain','txt',3,1,1,4,NULL,'2026-07-09 21:30:45',NULL,'2026-07-09 21:30:46',NULL,0),(2075213096120492034,'check-index.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783604332089_check-index.txt','knowledge/1783604332089_check-index.txt',16,'text/plain','txt',3,1,1,1,NULL,'2026-07-09 21:38:52',NULL,'2026-07-09 21:38:54',NULL,0),(2075214078611144705,'test-index-final.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783604566414_test-index-final.txt','knowledge/1783604566414_test-index-final.txt',16,'text/plain','txt',3,1,1,1,NULL,'2026-07-09 21:42:47',NULL,'2026-07-09 21:42:48',NULL,0),(2075214750777720833,'test-final-index.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783604726632_test-final-index.txt','knowledge/1783604726632_test-final-index.txt',19,'text/plain','txt',3,1,1,1,NULL,'2026-07-09 21:45:27',NULL,'2026-07-09 21:45:28',NULL,0),(2075217831531769857,'search-test-final.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783605461125_search-test-final.txt','knowledge/1783605461125_search-test-final.txt',88,'text/plain','txt',3,1,1,7,NULL,'2026-07-09 21:57:41',NULL,'2026-07-09 21:57:42',NULL,0),(2075218905936183298,'search-test-v2.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783605717333_search-test-v2.txt','knowledge/1783605717333_search-test-v2.txt',64,'text/plain','txt',3,1,1,5,NULL,'2026-07-09 22:01:58',NULL,'2026-07-09 22:01:59',NULL,0),(2075221795778170882,'test-redis-final.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783606406293_test-redis-final.txt','knowledge/1783606406293_test-redis-final.txt',64,'text/plain','txt',3,1,1,5,NULL,'2026-07-09 22:13:27',NULL,'2026-07-09 22:13:28',NULL,0),(2075222148766593026,'test-redis-v2.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783606490511_test-redis-v2.txt','knowledge/1783606490511_test-redis-v2.txt',58,'text/plain','txt',3,1,1,5,NULL,'2026-07-09 22:14:51',NULL,'2026-07-09 22:14:52',NULL,0),(2075224400260534274,'test-redis-new.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783607027266_test-redis-new.txt','knowledge/1783607027266_test-redis-new.txt',58,'text/plain','txt',3,1,1,5,NULL,'2026-07-09 22:23:47',NULL,'2026-07-09 22:23:49',NULL,0),(2075225010733056002,'test-redis-final.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783607172857_test-redis-final.txt','knowledge/1783607172857_test-redis-final.txt',64,'text/plain','txt',3,1,1,5,NULL,'2026-07-09 22:26:13',NULL,'2026-07-09 22:26:14',NULL,0),(2075228410665365505,'test-ft.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1783607983397_test-ft.txt','knowledge/1783607983397_test-ft.txt',30,'text/plain','txt',3,1,1,3,NULL,'2026-07-09 22:39:44',NULL,'2026-07-09 22:39:45',NULL,0),(2075228410665365506,'huiagent_upload_test.txt','https://yphbucket.oss-cn-shenzhen.aliyuncs.com/knowledge/1787238409044_huiagent_upload_test.txt','knowledge/1787238409044_huiagent_upload_test.txt',77,'application/octet-stream','txt',3,2,1,6,NULL,'2026-08-20 23:06:49','admin','2026-08-20 23:06:52','admin',1);
/*!40000 ALTER TABLE `knowledge_file` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_config`
--

DROP TABLE IF EXISTS `system_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '配置ID',
  `config_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '配置键',
  `config_value` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '配置值',
  `config_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'system' COMMENT '配置类型(system/ai/website/social)',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `is_public` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否公开(0=否 1=是)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `created_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `updated_by` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '更新人',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除(0=未删除 1=已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_config_key` (`config_key`) USING BTREE,
  KEY `idx_config_type` (`config_type`) USING BTREE,
  KEY `idx_is_public` (`is_public`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=19 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='系统配置表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_config`
--

LOCK TABLES `system_config` WRITE;
/*!40000 ALTER TABLE `system_config` DISABLE KEYS */;
INSERT INTO `system_config` VALUES (1,'ai.default_model','qwen-plus','ai','默认对话模型名称',1,'2026-08-20 22:13:29',NULL,'2026-08-20 22:13:29',NULL,0),(2,'ai.default_temperature','0.8','ai','默认温度参数(0-2)',1,'2026-08-20 22:13:29',NULL,'2026-08-20 22:13:29','admin',0),(3,'ai.default_max_tokens','4096','ai','默认最大Token输出数',1,'2026-08-20 22:13:29',NULL,'2026-08-20 22:13:29','admin',0),(4,'ai.system_prompt','你是一个智能助手，请用中文、专业、友好的方式回答用户问题。','ai','默认系统提示词',0,'2026-08-20 22:13:29',NULL,'2026-08-20 22:13:29',NULL,0),(5,'ai.enable_rag','true','ai','是否启用RAG知识库检索(布尔)',1,'2026-08-20 22:13:29',NULL,'2026-08-20 22:13:29',NULL,0),(6,'ai.pending_action_expire','600','ai','待确认操作过期时间(单位秒，默认10分钟)',0,'2026-08-20 22:13:29',NULL,'2026-08-20 22:13:29',NULL,0),(7,'ai.vector-index','huispace_rag','ai','Redis 向量索引名',0,'2026-08-20 22:13:29',NULL,'2026-08-20 22:13:44',NULL,0),(8,'ai.chat.api-key','sk-cqgnnfc2jmh31ym96uk68n3ipm2o82u5n7boxarcjj6sdaur','ai','文本模型 API 密钥',0,'2026-08-20 22:13:29',NULL,'2026-08-20 22:13:44',NULL,0),(9,'ai.chat.base-url','https://api.xiaomimimo.com/v1','ai','文本模型 API 地址',0,'2026-08-20 22:13:29',NULL,'2026-08-20 22:13:44',NULL,0),(10,'ai.chat.model','mimo-v2.5','ai','文本模型名称',0,'2026-08-20 22:13:29',NULL,'2026-08-20 22:13:44',NULL,0),(11,'ai.embedding.api-key','sk-3ab7950c3849475f92c15e990d07c256','ai','向量模型 API 密钥',0,'2026-08-20 22:13:29',NULL,'2026-08-20 22:13:44',NULL,0),(12,'ai.embedding.base-url','https://dashscope.aliyuncs.com/compatible-mode/v1','ai','向量模型 API 地址',0,'2026-08-20 22:13:29',NULL,'2026-08-20 22:13:44',NULL,0),(13,'ai.embedding.model','text-embedding-v4','ai','向量模型名称',0,'2026-08-20 22:13:29',NULL,'2026-08-20 22:13:44',NULL,0),(14,'ai.vision.api-key','sk-ccn7xffehsye0wlq2xqeann13aakuhek0jh6yzc5mvc36m7m','ai','视觉模型API密钥',0,'2026-08-20 22:13:29',NULL,'2026-08-20 22:13:44',NULL,0),(15,'ai.vision.base-url','https://api.xiaomimimo.com/v1','ai','视觉模型API地址',0,'2026-08-20 22:13:29',NULL,'2026-08-20 22:13:44',NULL,0),(16,'ai.vision.model','mimo-v2.5','ai','视觉模型名称',0,'2026-08-20 22:13:29',NULL,'2026-08-20 22:13:44',NULL,0),(18,'site.name','HuiAgent','site','site 配置',0,'2026-08-20 23:30:23','admin','2026-08-20 23:30:23','admin',0);
/*!40000 ALTER TABLE `system_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping routines for database 'hagent'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-08-21  0:30:54
