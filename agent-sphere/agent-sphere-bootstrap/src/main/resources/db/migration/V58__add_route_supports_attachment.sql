-- 路由能力位：是否支持附件（现阶段图片识别）
ALTER TABLE agent_model_route ADD COLUMN supports_attachment BOOLEAN NOT NULL DEFAULT FALSE;
