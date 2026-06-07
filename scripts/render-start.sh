#!/usr/bin/env sh
set -eu

CONFIG_DIR="${CONFIG_DIR:-/config}"
ENV_FILE="${ENV_FILE:-$CONFIG_DIR/env.yml}"
SERVER_FILE="${SERVER_FILE:-$CONFIG_DIR/server.yml}"

mkdir -p "$CONFIG_DIR"

if [ ! -f "$ENV_FILE" ]; then
  AUTH0_PUBLIC_KEY_VALUE="${AUTH0_PUBLIC_KEY:-}"
  if [ -n "${AUTH0_PUBLIC_KEY_TEXT:-}" ]; then
    AUTH0_PUBLIC_KEY_VALUE="$CONFIG_DIR/auth0.pem"
    printf "%s\n" "$AUTH0_PUBLIC_KEY_TEXT" > "$AUTH0_PUBLIC_KEY_VALUE"
  fi

  cat > "$ENV_FILE" <<EOF
DISABLE_AUTH: ${DISABLE_AUTH:-false}
OSM_VEX: ${OSM_VEX:-}
SPARKPOST_KEY: ${SPARKPOST_KEY:-}
SPARKPOST_EMAIL: ${SPARKPOST_EMAIL:-}
GTFS_DATABASE_URL: ${GTFS_DATABASE_URL:-}
GTFS_DATABASE_USER: ${GTFS_DATABASE_USER:-}
GTFS_DATABASE_PASSWORD: ${GTFS_DATABASE_PASSWORD:-}
MONGO_PROTOCOL: ${MONGO_PROTOCOL:-mongodb}
MONGO_URI: ${MONGO_URI:-}
MONGO_HOST: ${MONGO_HOST:-localhost:27017}
MONGO_DB_NAME: ${MONGO_DB_NAME:-datatools}
MONGO_USER: ${MONGO_USER:-}
MONGO_PASSWORD: ${MONGO_PASSWORD:-}
EOF

  if [ -n "${AUTH0_CLIENT_ID:-}" ]; then
    printf "AUTH0_CLIENT_ID: %s\n" "$AUTH0_CLIENT_ID" >> "$ENV_FILE"
  fi
  if [ -n "${AUTH0_DOMAIN:-}" ]; then
    printf "AUTH0_DOMAIN: %s\n" "$AUTH0_DOMAIN" >> "$ENV_FILE"
  fi
  if [ -n "${AUTH0_SECRET:-}" ]; then
    printf "AUTH0_SECRET: %s\n" "$AUTH0_SECRET" >> "$ENV_FILE"
  fi
  if [ -n "$AUTH0_PUBLIC_KEY_VALUE" ]; then
    printf "AUTH0_PUBLIC_KEY: %s\n" "$AUTH0_PUBLIC_KEY_VALUE" >> "$ENV_FILE"
  fi
  if [ -n "${AUTH0_API_CLIENT:-}" ]; then
    printf "AUTH0_API_CLIENT: %s\n" "$AUTH0_API_CLIENT" >> "$ENV_FILE"
  fi
  if [ -n "${AUTH0_API_SECRET:-}" ]; then
    printf "AUTH0_API_SECRET: %s\n" "$AUTH0_API_SECRET" >> "$ENV_FILE"
  fi
fi

if [ ! -f "$SERVER_FILE" ]; then
  cat > "$SERVER_FILE" <<EOF
application:
  title: ${APPLICATION_TITLE:-Data Tools}
  logo: ${APPLICATION_LOGO:-https://d2tyb7byn1fef9.cloudfront.net/ibi_group-128x128.png}
  logo_large: ${APPLICATION_LOGO_LARGE:-https://d2tyb7byn1fef9.cloudfront.net/ibi_group_black-512x512.png}
  client_assets_url: ${CLIENT_ASSETS_URL:-}
  shortcut_icon_url: ${SHORTCUT_ICON_URL:-https://d2tyb7byn1fef9.cloudfront.net/ibi-logo-original%402x.png}
  public_url: ${PUBLIC_URL:-}
  notifications_enabled: ${NOTIFICATIONS_ENABLED:-false}
  docs_url: ${DOCS_URL:-http://conveyal-data-tools.readthedocs.org}
  support_email: ${SUPPORT_EMAIL:-support@example.com}
  public_gtfs_contact_email: ${PUBLIC_GTFS_CONTACT_EMAIL:-support@example.com}
  port: ${PORT:-4000}
  data:
    gtfs: ${GTFS_DATA_DIR:-/var/data/gtfs}
    use_s3_storage: ${USE_S3_STORAGE:-false}
    s3_region: ${S3_REGION:-us-east-1}
    s3_endpoint: ${S3_ENDPOINT:-}
    gtfs_s3_bucket: ${GTFS_S3_BUCKET:-}
modules:
  enterprise:
    enabled: ${ENTERPRISE_ENABLED:-false}
    prefer_s3_links: ${PREFER_S3_LINKS:-false}
  editor:
    enabled: ${EDITOR_ENABLED:-true}
  deployment:
    enabled: ${DEPLOYMENT_ENABLED:-true}
    ec2:
      enabled: false
      default_ami: ''
      tag_key: ''
      tag_value: ''
    otp_download_url: ${OTP_DOWNLOAD_URL:-}
  user_admin:
    enabled: ${USER_ADMIN_ENABLED:-true}
  gtfsapi:
    enabled: ${GTFS_API_ENABLED:-true}
    load_on_fetch: ${GTFS_API_LOAD_ON_FETCH:-false}
  manager:
    normalizeFieldTransformation:
      defaultCapitalizationExceptions:
        - ACE
        - BART
      defaultSubstitutions:
        - description: "Replace '@' with 'at', and normalize space."
          pattern: "@"
          replacement: at
          normalizeSpace: true
        - description: "Replace '+' and '&' with 'and', and normalize space."
          pattern: "[+&]"
          replacement: and
          normalizeSpace: true
extensions:
  transitland:
    enabled: ${TRANSITLAND_ENABLED:-true}
    api: ${TRANSITLAND_API:-https://transit.land/api/v1/feeds}
  transitfeeds:
    enabled: ${TRANSITFEEDS_ENABLED:-true}
    api: ${TRANSITFEEDS_API:-http://api.transitfeeds.com/v1/getFeeds}
EOF
fi

exec java -XX:MaxRAMPercentage=95 -jar datatools-server.jar "$ENV_FILE" "$SERVER_FILE"
