#!/usr/bin/env bash
# ============================================
# Feishu Exporter —— 一键推送到 GitHub
# 前提：已在本机授权 GitHub 连接器（CodeBuddy 设置 → 连接器 → GitHub）
# 用法：
#   bash push_to_github.sh                    # 默认仓库名 Feishu-Exporter
#   bash push_to_github.sh my-repo-name       # 自定义仓库名
#   bash push_to_github.sh my-repo-name true  # 第三个参数=true 设为私有仓库
# ============================================
set -e

REPO_NAME="${1:-Feishu-Exporter}"
VISIBILITY="${2:-public}"   # public 或 private
SKILL_DIR="/root/.codebuddy/skills/github-connector"

echo "=== 1/4 获取 GitHub Token ==="
source "$SKILL_DIR/scripts/get_token.sh" github

if [ -z "$GITHUB_TOKEN" ]; then
  echo "[错误] 未获取到 GITHUB_TOKEN，请在 CodeBuddy 设置页面的连接器处重新授权 GitHub。"
  exit 1
fi

echo "=== 2/4 查询当前用户 ==="
USER_JSON=$(curl -s -H "Authorization: Bearer ${GITHUB_TOKEN}" "https://api.github.com/user")
OWNER=$(echo "$USER_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin).get('login',''))" 2>/dev/null)
if [ -z "$OWNER" ]; then
  echo "[错误] 无法获取 GitHub 用户名，Token 可能无效或已过期。"
  exit 1
fi
echo "当前用户：$OWNER"

echo "=== 3/4 创建远程仓库（若不存在）==="
PRIVATE_FLAG="false"
[ "$VISIBILITY" = "private" ] && PRIVATE_FLAG="true"
CREATE_RESP=$(curl -s -X POST -H "Authorization: Bearer ${GITHUB_TOKEN}" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/user/repos" \
  -d "{\"name\":\"${REPO_NAME}\",\"private\":${PRIVATE_FLAG},\"description\":\"飞书知识库/文档/表格导出器 - 免安装桌面工具\"}")
echo "$CREATE_RESP" | python3 -c "
import sys,json
d=json.load(sys.stdin)
if d.get('id'):
    print('仓库就绪：', d.get('html_url'))
else:
    msg=d.get('errors',[{}])[0].get('message') or d.get('message','未知错误')
    print('提示：', msg)
"

echo "=== 4/4 配置远程并推送 ==="
git remote remove origin 2>/dev/null || true
git remote add origin "https://oauth2:${GITHUB_TOKEN}@github.com/${OWNER}/${REPO_NAME}.git"
git branch -M main
git push -u origin main

echo ""
echo "=== 完成 ==="
echo "仓库地址：https://github.com/${OWNER}/${REPO_NAME}"
# 清除远程 URL 中的 token，避免明文留在 .git/config
git remote set-url origin "https://github.com/${OWNER}/${REPO_NAME}.git"
echo "已清理远程 URL 中的凭据。"
