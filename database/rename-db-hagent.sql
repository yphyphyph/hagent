-- HAgent 数据库改名：hagent -> hagent；用户表 hagent_user -> hagent_user
CREATE DATABASE IF NOT EXISTS `hagent` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

RENAME TABLE
  `hagent`.`ai_prompt`           TO `hagent`.`ai_prompt`,
  `hagent`.`chat_conversation`   TO `hagent`.`chat_conversation`,
  `hagent`.`chat_message`        TO `hagent`.`chat_message`,
  `hagent`.`chat_pending_action` TO `hagent`.`chat_pending_action`,
  `hagent`.`hagent_user`       TO `hagent`.`hagent_user`,
  `hagent`.`knowledge_file`      TO `hagent`.`knowledge_file`,
  `hagent`.`system_config`       TO `hagent`.`system_config`;

DROP DATABASE IF EXISTS `hagent`;