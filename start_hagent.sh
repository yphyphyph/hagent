#!/bin/bash
set -e

echo "=== 开始部署 HAgent ==="

# 禁止 Git 在非交互环境下卡死等待输入密码
export GIT_TERMINAL_PROMPT=0

# 1. 拉取代码
REPO_DIR="HAgent"
if [ -d "$REPO_DIR/.git" ] && git -C "$REPO_DIR" rev-parse --git-dir > /dev/null 2>&1; then
    echo ">>> 拉取最新代码..."
    if ! git -C "$REPO_DIR" pull; then
        echo "❌ 代码拉取失败！请检查网络或执行 'git config --global credential.helper store' 保存凭证"
        exit 1
    fi
else
    echo ">>> 克隆仓库..."
    rm -rf "$REPO_DIR" 2>/dev/null || true
    git clone https://gitee.com/yphyph/HAgent.git "$REPO_DIR"
fi

cd "$REPO_DIR"

# 2. 凭据检查：compose 需要根目录 .env 提供敏感环境变量
if [ ! -f ".env" ]; then
    echo "❌ 缺少 .env 文件！请执行: cp hagent-back/.env.example .env 并填入真实凭据"
    exit 1
fi

# 3. 彻底清理旧容器
echo ">>> 停止旧的容器..."
docker compose down --remove-orphans 2>/dev/null || true
docker rm -f hagent-backend hagent-frontend 2>/dev/null || true

# 4. 强制启用 BuildKit，大幅提升构建速度
export DOCKER_BUILDKIT=1
export COMPOSE_DOCKER_CLI_BUILD=1

# 5. 构建并启动
echo ">>> 构建并启动容器..."
docker compose up -d --build

# 6. 清理无用镜像
echo ">>> 清理旧镜像..."
docker image prune -f

echo "=== 部署完成 ==="
echo ">>> 查看日志: docker compose logs -f backend"
echo ">>> 门户地址: http://<服务器IP>:3002  后端: http://<服务器IP>:8099"
