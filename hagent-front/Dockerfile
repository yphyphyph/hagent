# syntax=docker/dockerfile:1
# ============================================================
# HAgent 前端 - 多阶段 Docker 构建（BuildKit 优化版）
# ============================================================

# 阶段1：构建
FROM node:20-alpine AS builder
WORKDIR /app

# 复制 package 文件
COPY package.json package-lock.json* ./

# 安装依赖（使用 BuildKit 缓存挂载，避免重复下载）
RUN --mount=type=cache,target=/root/.npm \
    npm ci --prefer-offline

# 复制源代码
COPY . .

# 构建生产版本
RUN npm run build

# 阶段2：Nginx 服务
FROM nginx:alpine

# 删除默认配置
RUN rm /etc/nginx/conf.d/default.conf

# 复制自定义 nginx 配置
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 复制构建产物
COPY --from=builder /app/dist /usr/share/nginx/html

# 暴露端口
EXPOSE 80

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1:80/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
