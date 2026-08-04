# Deploying mind-games behind AWS API Gateway

A step-by-step runbook to stand up this Spring Boot service on **ECS Fargate**, fronted by an
**internal ALB** and an **API Gateway HTTP API** (via VPC Link), backed by **RDS MySQL**.

> **Topology**
>
> ```
> Client ──HTTPS──▶ API Gateway (HTTP API) ──VPC Link──▶ Internal ALB ──▶ ECS Fargate task ──▶ RDS MySQL
> ```
>
> **Scaling note:** we run a **single Fargate task** (`desiredCount=1`). The app has in-process
> `@Scheduled` jobs (board-pool refill, refresh-token cleanup) that assume one instance. Do **not**
> scale to 2+ tasks until you add distributed locking (e.g. ShedLock) or split the schedulers into a
> dedicated worker — otherwise every task will run the refill and overshoot the target pool size.

---

## 1. Environment variable / secrets contract

The task definition supplies these. **Bold = no default; the app fails to start if unset.**

| Variable | Secret? | Default | Notes |
|---|---|---|---|
| `DB_URL` | no | `jdbc:mysql://CHANGE_ME...` | Set to the RDS endpoint JDBC URL. |
| `DB_USERNAME` | no | `CHANGE_ME_USER` | RDS app user. |
| **`DB_PASSWORD`** | **yes** | — | From Secrets Manager. |
| **`JWT_SECRET`** | **yes** | — | Base64, ≥32 bytes: `openssl rand -base64 32`. |
| `JWT_EXPIRATION_MINUTES` | no | `15` | Access-token lifetime. |
| `JWT_REFRESH_EXPIRATION_DAYS` | no | `30` | Refresh-token lifetime. |
| `JWT_REFRESH_CLEANUP_CRON` | no | `0 0 3 * * *` | Nightly token purge. |
| `COOKIE_SECURE` | no | `true` | Keep `true` in AWS (TLS terminates at the gateway). |
| `MANAGEMENT_PORT` | no | `9090` | Actuator port, separate from the app's 8080. ALB health check and metrics scrape target it. |
| **`BOARD_GRADING_EASY_MAX_THRESHOLD`** | **yes/no** | — | Max grade score for EASY. |
| **`BOARD_GRADING_MODERATE_MAX_THRESHOLD`** | **yes/no** | — | Max grade score for MODERATE. |
| **`BOARD_GRADING_HARD_MAX_THRESHOLD`** | **yes/no** | — | Max score for HARD; above it a board is skipped. |
| `BOARD_GENERATION_PREGENERATION_BOARD_COUNT` | no | `100` | Target pool size per difficulty. |
| `BOARD_GENERATION_LOW_WATERMARK` | no | `20` | Refill triggers below this. |
| `BOARD_GENERATION_MAX_ATTEMPTS_PER_BOARD` | no | `10` | Retries per board. |
| `BOARD_GENERATION_REFILL_CRON` | no | `0 */5 * * * *` | Pool-monitor schedule. |

The three `BOARD_GRADING_*` thresholds are plain ints, not sensitive — pass them as `environment`,
not `secrets`. `DB_PASSWORD` and `JWT_SECRET` must come from Secrets Manager.

---

## 2. Prerequisites

- AWS CLI v2 configured (`aws configure`) with rights for ECR, ECS, EC2/VPC, ELBv2, RDS,
  Secrets Manager, IAM, API Gateway v2, CloudWatch Logs.
- Docker with buildx.
- A VPC with **2 private subnets** (for ECS + RDS) and **2 public subnets** (only needed if you
  later put anything public-facing; the ALB here is internal). The default VPC works for a first pass.

Set shared shell variables (edit these):

```bash
export AWS_REGION=us-east-1
export ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export APP=mind-games
export VPC_ID=vpc-xxxxxxxx
export SUBNET_A=subnet-aaaa   # private
export SUBNET_B=subnet-bbbb   # private
```

---

## 3. Build & push the image to ECR

```bash
aws ecr create-repository --repository-name $APP --region $AWS_REGION

aws ecr get-login-password --region $AWS_REGION \
  | docker login --username AWS --password-stdin $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com

# Fargate is linux/amd64 by default; build for that even from an ARM laptop.
docker buildx build --platform linux/amd64 \
  -t $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$APP:latest --push .
```

---

## 4. Security groups

```bash
# App SG (ECS task) and DB SG (RDS)
APP_SG=$(aws ec2 create-security-group --group-name $APP-app --description "app" \
  --vpc-id $VPC_ID --query GroupId --output text)
ALB_SG=$(aws ec2 create-security-group --group-name $APP-alb --description "alb" \
  --vpc-id $VPC_ID --query GroupId --output text)
DB_SG=$(aws ec2 create-security-group --group-name $APP-db --description "db" \
  --vpc-id $VPC_ID --query GroupId --output text)

# ALB -> app on 8080
aws ec2 authorize-security-group-ingress --group-id $APP_SG \
  --protocol tcp --port 8080 --source-group $ALB_SG
# app -> RDS on 3306
aws ec2 authorize-security-group-ingress --group-id $DB_SG \
  --protocol tcp --port 3306 --source-group $APP_SG
# API Gateway VPC Link -> ALB on 443/80 is handled by the VPC Link SG (step 9);
# allow the VPC Link SG into the ALB there.
```

---

## 5. RDS MySQL

```bash
aws rds create-db-subnet-group --db-subnet-group-name $APP-db \
  --db-subnet-group-description "$APP db subnets" --subnet-ids $SUBNET_A $SUBNET_B

aws rds create-db-instance \
  --db-instance-identifier $APP \
  --engine mysql --engine-version 8.0 \
  --db-instance-class db.t4g.micro \
  --allocated-storage 20 \
  --db-name mindgames \
  --master-username admin \
  --manage-master-user-password \
  --vpc-security-group-ids $DB_SG \
  --db-subnet-group-name $APP-db \
  --no-publicly-accessible --backup-retention-period 7
```

`--manage-master-user-password` stores the master password in Secrets Manager automatically.
After it's `available`, grab the endpoint:

```bash
aws rds describe-db-instances --db-instance-identifier $APP \
  --query 'DBInstances[0].Endpoint.Address' --output text
```

> Create a **least-privilege app user** (not the master) for `DB_USERNAME`, and consider adding the
> RDS CA to the JDBC URL for full TLS verification in production
> (`&verifyServerCertificate=true&trustCertificateKeyStoreUrl=...`). Flyway builds the schema on
> first boot (`spring.flyway.enabled=true`), so no manual DDL is needed.

---

## 6. Secrets Manager

```bash
aws secretsmanager create-secret --name $APP/jwt-secret \
  --secret-string "$(openssl rand -base64 32)"

aws secretsmanager create-secret --name $APP/db-password \
  --secret-string 'THE_APP_USER_PASSWORD'
```

Note the returned ARNs — the task definition references them.

---

## 7. IAM roles

Two roles: an **execution role** (pulls the image, reads secrets, writes logs) and a **task role**
(the app's own AWS permissions — none needed yet).

```bash
# Execution role: attach the managed policy + Secrets read for your two secrets.
aws iam create-role --role-name $APP-exec \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
aws iam attach-role-policy --role-name $APP-exec \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
# Add an inline policy granting secretsmanager:GetSecretValue on the two secret ARNs.

aws iam create-role --role-name $APP-task \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
```

---

## 8. CloudWatch log group

```bash
aws logs create-log-group --log-group-name /ecs/$APP
```

---

## 9. Internal ALB + target group

```bash
ALB_ARN=$(aws elbv2 create-load-balancer --name $APP-alb --scheme internal \
  --type application --subnets $SUBNET_A $SUBNET_B --security-groups $ALB_SG \
  --query 'LoadBalancers[0].LoadBalancerArn' --output text)

# target-type ip because Fargate uses awsvpc networking. Traffic goes to 8080; the health check
# goes to 9090, where actuator listens (--health-check-port is independent of the traffic port).
TG_ARN=$(aws elbv2 create-target-group --name $APP-tg \
  --protocol HTTP --port 8080 --vpc-id $VPC_ID --target-type ip \
  --health-check-port 9090 \
  --health-check-path /actuator/health/readiness --health-check-interval-seconds 15 \
  --healthy-threshold-count 2 --query 'TargetGroups[0].TargetGroupArn' --output text)

# The ALB reaches the management port as well as the app port.
aws ec2 authorize-security-group-ingress --group-id $APP_SG \
  --protocol tcp --port 9090 --source-group $ALB_SG

aws elbv2 create-listener --load-balancer-arn $ALB_ARN \
  --protocol HTTP --port 80 \
  --default-actions Type=forward,TargetGroupArn=$TG_ARN
```

TLS terminates at API Gateway, so the internal ALB listener can be plain HTTP on 80. API Gateway
forwards `X-Forwarded-Proto: https`, which the app trusts (`forward-headers-strategy: framework`),
so `COOKIE_SECURE=true` behaves correctly.

**Why `/actuator/health/readiness` and not `/actuator/health`.** The aggregate folds in every health
indicator, including `mail` — so an unreachable SES marks the task unhealthy and ECS kills a service
that can still deal boards and authenticate. The readiness group is scoped to `readinessState,db`
(`application.yaml`), which is the set that actually determines whether this instance can serve a
request. Note that with RDS unreachable the endpoint *hangs* on Hikari's connection timeout rather
than answering DOWN quickly; the ALB's health-check timeout (default 5s, and it must stay below the
15s interval) is what converts that into a failed check.

---

## 10. ECS cluster, task definition, service

Register the task definition (`taskdef.json` — fill in the ARNs, thresholds, and RDS endpoint):

```jsonc
{
  "family": "mind-games",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512", "memory": "1024",
  "executionRoleArn": "arn:aws:iam::ACCOUNT_ID:role/mind-games-exec",
  "taskRoleArn": "arn:aws:iam::ACCOUNT_ID:role/mind-games-task",
  "containerDefinitions": [{
    "name": "app",
    "image": "ACCOUNT_ID.dkr.ecr.REGION.amazonaws.com/mind-games:latest",
    "portMappings": [{ "containerPort": 8080 }, { "containerPort": 9090 }],
    "environment": [
      { "name": "DB_URL", "value": "jdbc:mysql://RDS_ENDPOINT:3306/mindgames?useSSL=true&serverTimezone=UTC&characterEncoding=utf8" },
      { "name": "DB_USERNAME", "value": "appuser" },
      { "name": "COOKIE_SECURE", "value": "true" },
      { "name": "BOARD_GRADING_EASY_MAX_THRESHOLD", "value": "15" },
      { "name": "BOARD_GRADING_MODERATE_MAX_THRESHOLD", "value": "45" },
      { "name": "BOARD_GRADING_HARD_MAX_THRESHOLD", "value": "120" }
    ],
    "secrets": [
      { "name": "JWT_SECRET",  "valueFrom": "arn:aws:secretsmanager:REGION:ACCOUNT_ID:secret:mind-games/jwt-secret" },
      { "name": "DB_PASSWORD", "valueFrom": "arn:aws:secretsmanager:REGION:ACCOUNT_ID:secret:mind-games/db-password" }
    ],
    "healthCheck": {
      "command": ["CMD-SHELL", "wget -qO- http://localhost:9090/actuator/health/readiness || exit 1"],
      "interval": 30, "timeout": 5, "retries": 3, "startPeriod": 90
    },
    "logConfiguration": {
      "logDriver": "awslogs",
      "options": {
        "awslogs-group": "/ecs/mind-games",
        "awslogs-region": "REGION",
        "awslogs-stream-prefix": "app"
      }
    }
  }]
}
```

```bash
aws ecs create-cluster --cluster-name $APP
aws ecs register-task-definition --cli-input-json file://taskdef.json

aws ecs create-service --cluster $APP --service-name $APP \
  --task-definition mind-games --launch-type FARGATE --desired-count 1 \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNET_A,$SUBNET_B],securityGroups=[$APP_SG],assignPublicIp=DISABLED}" \
  --load-balancers "targetGroupArn=$TG_ARN,containerName=app,containerPort=8080" \
  --health-check-grace-period-seconds 120
```

> Private subnets need a route to pull the image and read secrets: either a **NAT gateway** or
> **VPC endpoints** for `ecr.api`, `ecr.dkr`, `s3` (gateway), `secretsmanager`, and `logs`.
> Endpoints avoid NAT data charges.
>
> `startPeriod`/grace of 90–120s covers JVM warmup + Flyway migration on first boot.

---

## 11. API Gateway HTTP API + VPC Link

```bash
# VPC Link lives in the private subnets and needs an SG allowed into the ALB.
VPCLINK_SG=$(aws ec2 create-security-group --group-name $APP-vpclink \
  --description "vpc link" --vpc-id $VPC_ID --query GroupId --output text)
aws ec2 authorize-security-group-ingress --group-id $ALB_SG \
  --protocol tcp --port 80 --source-group $VPCLINK_SG

VPCLINK_ID=$(aws apigatewayv2 create-vpc-link --name $APP \
  --subnet-ids $SUBNET_A $SUBNET_B --security-group-ids $VPCLINK_SG \
  --query VpcLinkId --output text)

API_ID=$(aws apigatewayv2 create-api --name $APP --protocol-type HTTP \
  --query ApiId --output text)

LISTENER_ARN=$(aws elbv2 describe-listeners --load-balancer-arn $ALB_ARN \
  --query 'Listeners[0].ListenerArn' --output text)

INTEG_ID=$(aws apigatewayv2 create-integration --api-id $API_ID \
  --integration-type HTTP_PROXY --integration-method ANY \
  --connection-type VPC_LINK --connection-id $VPCLINK_ID \
  --integration-uri $LISTENER_ARN --payload-format-version 1.0 \
  --query IntegrationId --output text)

# The catch-all serves everything. The named routes exist so they can carry their own
# throttle bucket (see §11a) — they all share the one integration above.
for RK in 'ANY /{proxy+}' \
          'POST /api/v1/register' \
          'POST /api/v1/authenticate_user'; do
  aws apigatewayv2 create-route --api-id $API_ID \
    --route-key "$RK" --target integrations/$INTEG_ID
done

aws apigatewayv2 create-stage --api-id $API_ID --stage-name '$default' --auto-deploy
```

The default invoke URL is printed by:

```bash
aws apigatewayv2 get-api --api-id $API_ID --query ApiEndpoint --output text
```

Route keys are **method-specific**: `POST /api/v1/register` does not match a `GET`, and a request
that matches no route gets API Gateway's own `404 {"message":"Not Found"}` — no backend headers on
the response, which is how you tell a routing miss from an application 404. A route created without
`--target` still *wins* the match and then fails with a 500, so it shadows the catch-all rather than
falling through to it. Audit with:

```bash
aws apigatewayv2 get-routes --api-id $API_ID \
  --query 'Items[].{Route:RouteKey,Target:Target}' --output table
```

For a real domain: request an **ACM cert**, `create-domain-name`, and `create-api-mapping`.

---

## 11a. Per-route throttling

Throttling attaches to the **stage**, keyed by route key. `--route-settings` replaces the whole map,
so always pass every route you want configured, not just the new one:

```bash
aws apigatewayv2 update-stage --api-id $API_ID --stage-name '$default' \
  --default-route-settings '{"ThrottlingRateLimit":100,"ThrottlingBurstLimit":200,"DetailedMetricsEnabled":true}' \
  --route-settings '{
    "POST /api/v1/register":          {"ThrottlingRateLimit":1,"ThrottlingBurstLimit":3},
    "POST /api/v1/authenticate_user": {"ThrottlingRateLimit":5,"ThrottlingBurstLimit":10}
  }'
```

Rate is sustained requests/second, burst is token-bucket depth. Over the limit the gateway returns
429 without touching the ALB.

> **This bucket is shared across all callers.** It caps aggregate load on the backend, but a single
> abusive IP can consume the entire per-route budget and lock everyone else out. It is *not*
> per-client rate limiting.

Per-caller limits are not available on an HTTP API: API keys and usage plans are REST-API-only, and
**AWS WAF cannot be associated with an HTTP API** (`apigatewayv2`). The options, in order of least
disruption:

- **WAF on the ALB.** Associates fine with an internal ALB. Use a rate-based rule with
  `ForwardedIPConfig` set to read `X-Forwarded-For` — from the ALB's perspective every request
  arrives from the VPC Link ENIs, so without that the per-IP counters collapse into one.
- **CloudFront + WAF** in front of the gateway. Adds edge caching; adds a hop.
- **In-app** (e.g. Bucket4j). The only layer that can limit by *user identity* rather than IP, and
  the right place for per-account rules — but the request has already cost you an ECS request.

A **Lambda authorizer** (REQUEST type, with result caching) can reject unauthenticated traffic at the
gateway. The native JWT authorizer will not work here: it requires an OIDC issuer with a JWKS
endpoint, and this app signs its own HMAC tokens with jjwt.

---

## 12. Verify

```bash
API=$(aws apigatewayv2 get-api --api-id $API_ID --query ApiEndpoint --output text)
curl -s $API/api/ping                  # public endpoint

# Actuator is not reachable here: it listens on 9090, and no API Gateway route targets it.
# Check health from inside the VPC, or read the target group's view of it:
aws elbv2 describe-target-health --target-group-arn $TG_ARN \
  --query 'TargetHealthDescriptions[].TargetHealth.State' --output text

# Register (public). 201 + {"username":"..."} on success, 409 on a duplicate.
curl -i -X POST "$API/api/v1/register" -H 'Content-Type: application/json' \
  -d '{"username":"testuser","password":"hunter2password","email":"testuser@example.com"}'
```

Read the *headers* to locate a failure, not just the status:

| Symptom | Where it came from |
|---|---|
| `404 {"message":"Not Found"}`, only an `apigw-requestid` header | API Gateway — no route matched that method+path |
| `500`, no backend headers | route matched but has no integration target |
| Any status carrying `x-frame-options` / `x-content-type-options` | reached Spring; it's an application response |

Also confirm in **CloudWatch Logs** (`/ecs/mind-games`) that:
- Flyway ran the migration (`V1__Initial_schema`),
- the board-pool refill logged its first run within ~5 minutes.

---

## 13. CloudFront + custom domain + WAF

```
viewer ──► CloudFront ──┬── default behavior ──► S3 (frontend, private, OAC)
           (+ WAF)      └── /api/* behavior ───► API Gateway ──► VPC Link ──► ALB
```

CloudFront is what makes per-IP rate limiting possible at all: **WAF cannot attach to an HTTP API**,
and on the ALB it would see only the VPC Link ENIs, collapsing every caller into one counter. On
CloudFront it sees the real viewer IP.

**Certificate.** ACM, **us-east-1 only** — CloudFront reads certs from no other region, and one
issued in `us-west-2` will not appear in the distribution's dropdown. Request `cortexclash.org` plus
`*.cortexclash.org` on a single cert so future hostnames need no new validation.

**DNS (Namecheap).** Namecheap **auto-appends the domain to the Host field**. For an ACM validation
record named `_1aee….cortexclash.org`, enter Host = `_1aee…` alone; pasting the full name silently
creates `_1aee….cortexclash.org.cortexclash.org` and the cert sits in *Pending validation* forever.
Delete Namecheap's default parking records (`CNAME www → parkingpage.namecheap.com` and the URL
redirect) or they win over the records you add. Apex needs Namecheap's own **ALIAS** type (a CNAME at
`@` is illegal DNS); `www` is a plain CNAME. Both point at `<dist>.cloudfront.net`.

**Origin (API).** Domain `q3wkefts09.execute-api.us-west-2.amazonaws.com`, HTTPS only, **origin path
empty** — CloudFront *prepends* origin path, so `/api` there turns `/api/ping` into `/api/api/ping`.
Add a custom header `X-Origin-Verify: <secret>` (see below).

**`/api/*` behavior.** The two settings that break this if you get them wrong:

- **Cache policy `CachingDisabled`** — every endpoint is authenticated and per-user; caching would
  serve one user's boards to another.
- **Origin request policy `AllViewerExceptHostHeader`** — forwards `Authorization`, cookies and query
  strings, which is what keeps bearer tokens and the refresh cookie alive. Note *ExceptHostHeader*:
  plain `AllViewer` forwards the viewer's `Host` (your domain) to execute-api, which does not
  recognize it and answers **403** — indistinguishable from an auth failure at a glance.
- Allowed methods must include POST/PUT/PATCH/DELETE; the default is GET/HEAD only.

**WAF.** Create the ACL yourself (us-east-1, scope *CloudFront*) with a rate-based rule — limit 500
per 5 min, aggregate on Source IP, Block — rather than using the create-distribution wizard's
"security protections", which builds a *second* ACL with managed rules and no rate limit. A
distribution can hold only one ACL. Managed rule groups can false-positive on JSON bodies; add them
in **Count** mode first.

**Closing the bypass.** execute-api stays publicly reachable, so anyone with that URL skips CloudFront
and therefore skips WAF. CloudFront injects `X-Origin-Verify` on every origin request (and strips any
same-named header from the viewer, so it cannot be forged); `OriginVerificationFilter` rejects
anything arriving without it. The filter guards the application port only: actuator listens on 9090,
which the filter never sees, so the ALB health check and the metrics scrape reach it without a header
while every `/api` route on 8080 stays gated.

**Deploy order matters.** The filter is disabled while `ORIGIN_VERIFY_SECRET` is blank, so:

1. Create the distribution with the custom header, and confirm traffic works through the domain.
2. *Then* store the secret and add it to `taskdef.json` alongside `JWT_SECRET`:
   ```json
   { "name": "ORIGIN_VERIFY_SECRET", "valueFrom": "arn:aws:secretsmanager:us-west-2:851725201833:secret:dev/mind-games-dev/origin-verify-secret-XXXX:ORIGIN_VERIFY_SECRET::" }
   ```
3. Redeploy. Direct calls to execute-api now return 403 — including any test scripts still pointed
   at it.

`infra/cloudfront/setup.sh` expresses the same thing as CLI calls.

---

## 14. Automated nightly deployment (EventBridge Scheduler)

CI pushes a fresh `:latest` to ECR on every `master` merge (§3, `.github/workflows/ci.yml`), but
pushing an image does **not** restart the running task — the service keeps serving the old image
until something forces a new deployment. An **EventBridge Scheduler** schedule does that on a fixed
cadence: once a night it calls ECS `UpdateService` with `forceNewDeployment`, which starts a fresh
task, re-pulls `:latest`, and — because the service owns the target group — swaps the new task's IP
into `mind-games-tg` on its own. No Lambda, no pipeline: one scheduled API call.

> **Why a service, not a bare task.** `forceNewDeployment` only exists on an ECS *service*. A
> standalone `RunTask` has no roll-to-newest-image API, and nothing registers its ENI IP into the
> ALB target group for you. Run the app as a service (§10) or none of this — including the automatic
> ALB registration — works.

**Prerequisite: stop-then-start deployment config.** A default roll (`minimumHealthyPercent=100`,
`maximumPercent=200`) briefly runs two tasks, and two tasks both fire the `@Scheduled` board-pool
refill and overshoot the pool (the single-instance rule from the top of this doc). Pin the service
to one task at a time so a redeploy stops the old task before starting the new one:

```bash
aws ecs update-service --cluster $APP --service <service-name> \
  --deployment-configuration 'minimumHealthyPercent=0,maximumPercent=100'
```

The cost is a short 503 window each deploy while there is no healthy target — the gap the
"Zero-downtime deploys" follow-up below says to accept until the scheduler-scaling problem is fixed.

**IAM role (create it first).** The scheduler assumes a role to make the call. For a *universal*
target (any AWS API) the console's "Create new role for this schedule" is **disabled** — that only
works for templated targets — so create the role up front:

```bash
# Trust: only EventBridge Scheduler in this account may assume it.
aws iam create-role --role-name $APP-nightly-deploy \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"scheduler.amazonaws.com"},"Action":"sts:AssumeRole","Condition":{"StringEquals":{"aws:SourceAccount":"'$ACCOUNT_ID'"}}}]}'

# Permission: one action, one service. forceNewDeployment reuses the current task
# definition, so no iam:PassRole is needed.
aws iam put-role-policy --role-name $APP-nightly-deploy --policy-name update-service \
  --policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ecs:UpdateService","Resource":"arn:aws:ecs:'$AWS_REGION':'$ACCOUNT_ID':service/'$APP'/<service-name>"}]}'
```

**The schedule.** Fires daily at 20:00 `America/Los_Angeles`. Naming the timezone (rather than baking
a UTC offset into the cron) makes it ride DST automatically, so it stays 8 PM Pacific year-round:

```bash
aws scheduler create-schedule --name $APP-nightly-deploy \
  --schedule-expression 'cron(0 20 * * ? *)' \
  --schedule-expression-timezone 'America/Los_Angeles' \
  --flexible-time-window '{"Mode":"OFF"}' \
  --target '{
    "Arn": "arn:aws:scheduler:::aws-sdk:ecs:updateService",
    "RoleArn": "arn:aws:iam::'$ACCOUNT_ID':role/'$APP'-nightly-deploy",
    "Input": "{\"Cluster\":\"'$APP'\",\"Service\":\"<service-name>\",\"ForceNewDeployment\":true}"
  }'
```

The universal-target `Input` uses the AWS SDK's PascalCase shape (`Cluster`/`Service`/
`ForceNewDeployment`), **not** the lowercase JSON-API field names.

> **The current deployment.** The live service is named **`mind-games-backend-service`** (the ECS
> console's default when the service is created from the task-def family), not `mind-games` —
> substitute it for `<service-name>` above and in the role's resource ARN.

**Caveats.**

- It redeploys **every night regardless of whether the image changed**, so a quiet day still takes
  the 503 gap for nothing. A digest-gated variant — compare `:latest`'s digest to the running task's
  and deploy only on a diff — needs a Lambda target instead of the direct API call.
- It deploys whatever `:latest` points to at fire time, i.e. the last `master` push. It has no notion
  of a pinned or manually approved build.

**Verify without waiting for 8 PM.** Scheduler has no run-now. Temporarily set the cron a couple of
minutes ahead (same timezone), watch for a new deployment and a `registered N targets` line in the
service's **Events** tab, then restore `cron(0 20 * * ? *)`. A non-zero `TargetErrorCount` under
**CloudWatch → AWS/Scheduler** points at a role trust/permission problem rather than a deploy failure.

---

## Follow-ups / not covered here

- **Horizontal scaling** requires fixing the single-instance scheduler assumption first (ShedLock or
  a separate worker service). Until then keep `desired-count 1`.
- **Zero-downtime deploys**: with one task there's a brief gap on redeploy; ECS rolling deploy with
  `minimumHealthyPercent=100`/`maximumPercent=200` needs 2 tasks, which reintroduces the scheduler
  problem. Accept the gap, or solve scheduling first. The nightly auto-deploy (§14) takes the gap
  deliberately, running `minimumHealthyPercent=0`/`maximumPercent=100`.
- **RDS TLS verification** and API Gateway auth (a Lambda authorizer — the native JWT authorizer needs
  an OIDC/JWKS issuer, and this app signs its own HMAC tokens) are left as hardening steps.
