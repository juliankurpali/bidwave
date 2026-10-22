#!/usr/bin/env bash
# Seed a lot into DynamoDB (always-on stack must be deployed).
set -euo pipefail

REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-central-1}}"
TABLE="${LOTS_TABLE:-bidwave-Lots}"
LOT_ID="${LOT_ID:-lot-1}"
TITLE="${TITLE:-Charity vase}"
HIGH_BID="${HIGH_BID:-0}"
HOURS_OPEN="${HOURS_OPEN:-24}"

STARTS="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
if date -u -v+"${HOURS_OPEN}"H +"%Y-%m-%dT%H:%M:%SZ" >/dev/null 2>&1; then
  ENDS="$(date -u -v+"${HOURS_OPEN}"H +"%Y-%m-%dT%H:%M:%SZ")"
else
  ENDS="$(date -u -d "+${HOURS_OPEN} hours" +"%Y-%m-%dT%H:%M:%SZ")"
fi

echo "Seeding lot $LOT_ID into $TABLE ($REGION)"
aws dynamodb put-item \
  --region "$REGION" \
  --table-name "$TABLE" \
  --item "{
    \"lotId\": {\"S\": \"$LOT_ID\"},
    \"title\": {\"S\": \"$TITLE\"},
    \"highBid\": {\"N\": \"$HIGH_BID\"},
    \"highBidder\": {\"S\": \"seed\"},
    \"startsAt\": {\"S\": \"$STARTS\"},
    \"endsAt\": {\"S\": \"$ENDS\"},
    \"version\": {\"N\": \"1\"}
  }"

echo "Done. endsAt=$ENDS"
aws dynamodb get-item --region "$REGION" --table-name "$TABLE" --key "{\"lotId\":{\"S\":\"$LOT_ID\"}}"
