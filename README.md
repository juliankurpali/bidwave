# BidWave

Live auction demo on AWS: Java 17 Lambdas, API Gateway, SQS FIFO, DynamoDB, S3 + CloudFront.

The AWS stack from the original demo was torn down. This repo is the source and the course presentation.

## What is not in git (on purpose)

- `frontend/config.json` — API URL and API key after deploy
- `infra/samconfig.toml` — local deploy settings
- AWS keys, `.env`, and `.pem` files

Copy `frontend/config.example.json` and `infra/samconfig.toml.example` if you deploy your own stack.

## Local run

```bash
./scripts/run-local.sh
```

Then open http://127.0.0.1:8080/

## Tests

```bash
mvn test
```
