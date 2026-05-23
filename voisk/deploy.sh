#!/bin/bash

# --- 환경 설정 ---
SECRET_ID="voisk"
REGION="ap-northeast-2"
COMPOSE_FILE="docker-compose.yml"

set +o histexpand

# --- AWS CLI 및 jq 확인 ---
if ! command -v aws &> /dev/null || ! command -v jq &> /dev/null; then
    echo "ERROR: AWS CLI 또는 jq가 설치되어 있지 않습니다." >&2
    exit 1
fi

# --- 1/4: Secrets Manager에서 값 가져오기 ---
echo "--- 1/4: Secrets Manager에서 정보 가져오기 ---"
SECRET_JSON=$(aws secretsmanager get-secret-value \
    --secret-id "${SECRET_ID}" \
    --region "${REGION}" \
    --query "SecretString" \
    --output text 2>/dev/null)

if [ $? -ne 0 ] || [ -z "$SECRET_JSON" ]; then
    echo "ERROR: Secrets Manager 정보 로드 실패. IAM 권한/Secret ID를 확인하세요." >&2
    exit 1
fi

# --- 2/4: 환경변수 export ---
echo "--- 2/4: 환경 변수 설정하기 ---"
export MYSQL_URL=$(echo "$SECRET_JSON" | jq -r '.MYSQL_URL')
export MYSQL_USERNAME=$(echo "$SECRET_JSON" | jq -r '.MYSQL_USERNAME')
export MYSQL_PASSWORD=$(echo "$SECRET_JSON" | jq -r '.MYSQL_PASSWORD' | tr -d '\n\r')
export POSTGRES_URL=$(echo "$SECRET_JSON" | jq -r '.POSTGRES_URL')
export POSTGRES_USERNAME=$(echo "$SECRET_JSON" | jq -r '.POSTGRES_USERNAME')
export POSTGRES_PASSWORD=$(echo "$SECRET_JSON" | jq -r '.POSTGRES_PASSWORD' | tr -d '\n\r')
export GEMINI_API_KEY=$(echo "$SECRET_JSON" | jq -r '.GEMINI_API_KEY')
export EMBED_SERVER_URL=$(echo "$SECRET_JSON" | jq -r '.EMBED_SERVER_URL')
export EMBED_MODEL=$(echo "$SECRET_JSON" | jq -r '.EMBED_MODEL')
export AWS_ACCOUNT_ID=$(echo "$SECRET_JSON" | jq -r '.AWS_ACCOUNT_ID')

# --- 3/4: ECR 로그인 및 컨테이너 실행 ---
echo "--- 3/4: ECR 로그인 및 컨테이너 Pull ---"
aws ecr get-login-password --region "${REGION}" | \
    docker login --username AWS --password-stdin \
    "${AWS_ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

docker compose -f "${COMPOSE_FILE}" pull fastapi springboot
docker compose -f "${COMPOSE_FILE}" up -d

echo "--- 4/4: 완료 ---"
echo "배포 완료!"
echo "상태 확인: docker ps"
echo "로그 확인: docker logs voisk-springboot"
