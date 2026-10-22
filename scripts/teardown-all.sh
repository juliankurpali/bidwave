#!/usr/bin/env bash
# Tear down BidWave stacks. Prefer demo-hour first (ALB cost), then always-on SAM.
set -euo pipefail

REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-central-1}}"
SAM_STACK="${SAM_STACK:-bidwave}"
DEMO_STACK="${DEMO_STACK:-bidwave-demo-hour}"

echo "=== BidWave teardown-all ==="
echo "Region: $REGION"
echo "1) Delete demo-hour (ALB/EC2) if present"
echo "2) sam delete always-on stack"
echo "3) Empty/delete UI bucket if stack delete leaves it"
echo
read -r -p "Type TEARDOWN to confirm: " confirm
if [[ "$confirm" != "TEARDOWN" ]]; then
  echo "Aborted."
  exit 1
fi

if aws cloudformation describe-stacks --stack-name "$DEMO_STACK" --region "$REGION" >/dev/null 2>&1; then
  echo "Deleting $DEMO_STACK ..."
  aws cloudformation delete-stack --stack-name "$DEMO_STACK" --region "$REGION"
  aws cloudformation wait stack-delete-complete --stack-name "$DEMO_STACK" --region "$REGION" || true
else
  echo "No demo-hour stack ($DEMO_STACK)."
fi

if command -v sam >/dev/null 2>&1; then
  echo "sam delete --stack-name $SAM_STACK"
  sam delete --stack-name "$SAM_STACK" --region "$REGION" --no-prompts || \
    aws cloudformation delete-stack --stack-name "$SAM_STACK" --region "$REGION"
else
  echo "SAM CLI not found — deleting CloudFormation stack $SAM_STACK"
  aws cloudformation delete-stack --stack-name "$SAM_STACK" --region "$REGION"
fi

echo
echo "Reminders:"
echo "  - Empty S3 UI bucket if delete failed on non-empty bucket, then re-delete stack"
echo "  - Delete CloudWatch log groups /aws/lambda/bidwave-* if desired"
echo "  - Check Cost Explorer + billing alarms"
echo "  - Never leave ALB running overnight"
