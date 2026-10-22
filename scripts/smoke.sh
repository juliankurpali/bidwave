#!/usr/bin/env bash
# Smoke: place bid → wait → get lot → assert highBid increased.
set -euo pipefail

API_URL="${API_URL:?Set API_URL to stack ApiUrl output}"
API_KEY="${API_KEY:?Set API_KEY (from get-api-key --include-value)}"
LOT_ID="${LOT_ID:-lot-1}"
BIDDER="${BIDDER:-smoke-tester}"
AMOUNT="${AMOUNT:-$(date +%s)}"

API_URL="${API_URL%/}"

echo "GET current lot..."
BEFORE="$(curl -fsS "$API_URL/lots/$LOT_ID")"
echo "$BEFORE"

echo "POST bid amount=$AMOUNT ..."
curl -fsS -X POST "$API_URL/bids" \
  -H "content-type: application/json" \
  -H "x-api-key: $API_KEY" \
  -d "{\"lotId\":\"$LOT_ID\",\"bidder\":\"$BIDDER\",\"amount\":$AMOUNT}"
echo

echo "Waiting 3s for settle..."
sleep 3

AFTER="$(curl -fsS "$API_URL/lots/$LOT_ID")"
echo "$AFTER"

HIGH="$(echo "$AFTER" | python3 -c "import sys,json; print(json.load(sys.stdin).get('highBid'))" 2>/dev/null \
  || echo "$AFTER" | sed -n 's/.*"highBid":\([0-9.]*\).*/\1/p')"

python3 - <<PY
amount = float("$AMOUNT")
high = float("$HIGH")
assert high >= amount, f"expected highBid >= {amount}, got {high}"
print(f"SMOKE OK highBid={high}")
PY
