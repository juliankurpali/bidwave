#!/usr/bin/env bash
# Seed three demo lots into DynamoDB (always-on stack must be deployed).
set -euo pipefail

REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-central-1}}"
TABLE="${LOTS_TABLE:-bidwave-Lots}"
HOURS_OPEN="${HOURS_OPEN:-24}"

STARTS="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
if date -u -v+"${HOURS_OPEN}"H +"%Y-%m-%dT%H:%M:%SZ" >/dev/null 2>&1; then
  ENDS="$(date -u -v+"${HOURS_OPEN}"H +"%Y-%m-%dT%H:%M:%SZ")"
else
  ENDS="$(date -u -d "+${HOURS_OPEN} hours" +"%Y-%m-%dT%H:%M:%SZ")"
fi

seed() {
  local id="$1"
  local title="$2"
  local high="$3"
  echo "Seeding $id — $title @ $high"
  aws dynamodb put-item \
    --region "$REGION" \
    --table-name "$TABLE" \
    --item "{
      \"lotId\": {\"S\": \"$id\"},
      \"title\": {\"S\": \"$title\"},
      \"highBid\": {\"N\": \"$high\"},
      \"highBidder\": {\"S\": \"Auctioneer\"},
      \"startsAt\": {\"S\": \"$STARTS\"},
      \"endsAt\": {\"S\": \"$ENDS\"},
      \"version\": {\"N\": \"1\"}
    }"
}

echo "Table=$TABLE region=$REGION endsAt=$ENDS"
seed "lot-mug" "Vintage cloud mug" "5"
seed "lot-hoodie" "Signed BidWave hoodie" "20"
seed "lot-laptop" "Mystery refurbished laptop" "50"
echo "Done. Three live lots ready."
