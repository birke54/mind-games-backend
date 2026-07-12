#!/usr/bin/env bash
#
# CloudFront in front of the HTTP API + an S3-hosted frontend, with WAF on the distribution.
#
#   viewer ──► CloudFront ──┬── default behavior ──► S3 (frontend, private, OAC)
#              (+ WAF)      └── /api/* behavior ───► API Gateway (q3wkefts09)
#
# Run top to bottom. Steps 1-2 are one-time and must happen in us-east-1 regardless of where the
# rest of the stack lives; everything else is us-west-2.
set -euo pipefail

APP=mind-games
DOMAIN=api.example.com          # <-- the name the frontend and API will be served from
HOSTED_ZONE_ID=ZXXXXXXXXXXXXX   # <-- Route 53 zone for that domain
API_ID=q3wkefts09
REGION=us-west-2
API_ORIGIN_DOMAIN="${API_ID}.execute-api.${REGION}.amazonaws.com"

# ---------------------------------------------------------------------------------------------
# 1. ACM certificate — MUST be in us-east-1. CloudFront reads certs only from that region, no
#    matter where the distribution serves from. A cert in us-west-2 is invisible to it.
# ---------------------------------------------------------------------------------------------
CERT_ARN=$(aws acm request-certificate --region us-east-1 \
  --domain-name "$DOMAIN" --validation-method DNS \
  --query CertificateArn --output text)

# Emit the DNS validation record, then create it in Route 53 and wait for ISSUED.
aws acm describe-certificate --region us-east-1 --certificate-arn "$CERT_ARN" \
  --query 'Certificate.DomainValidationOptions[0].ResourceRecord'
aws acm wait certificate-validated --region us-east-1 --certificate-arn "$CERT_ARN"

# ---------------------------------------------------------------------------------------------
# 2. WAF web ACL — scope CLOUDFRONT, also us-east-1 only.
#    Rate-based rule: >500 requests in any 5-minute window from one IP gets blocked. Because the
#    ACL is on CloudFront, WAF sees the real viewer IP; on the ALB it would have seen only the
#    VPC Link ENIs and every caller would have collapsed into one counter.
# ---------------------------------------------------------------------------------------------
cat > /tmp/waf-rules.json <<'JSON'
[{
  "Name": "RateLimitPerIP",
  "Priority": 0,
  "Statement": {
    "RateBasedStatement": { "Limit": 500, "AggregateKeyType": "IP" }
  },
  "Action": { "Block": {} },
  "VisibilityConfig": {
    "SampledRequestsEnabled": true,
    "CloudWatchMetricsEnabled": true,
    "MetricName": "RateLimitPerIP"
  }
}]
JSON

WAF_ARN=$(aws wafv2 create-web-acl --region us-east-1 --scope CLOUDFRONT \
  --name "${APP}-cf" --default-action Allow={} \
  --rules file:///tmp/waf-rules.json \
  --visibility-config SampledRequestsEnabled=true,CloudWatchMetricsEnabled=true,MetricName="${APP}-cf" \
  --query 'Summary.ARN' --output text)

# ---------------------------------------------------------------------------------------------
# 3. S3 bucket for the frontend. Stays private; CloudFront reaches it via Origin Access Control.
# ---------------------------------------------------------------------------------------------
BUCKET="${APP}-frontend-$(aws sts get-caller-identity --query Account --output text)"
aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
  --create-bucket-configuration LocationConstraint="$REGION"
aws s3api put-public-access-block --bucket "$BUCKET" \
  --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

OAC_ID=$(aws cloudfront create-origin-access-control --origin-access-control-config \
  "Name=${APP}-s3,OriginAccessControlOriginType=s3,SigningBehavior=always,SigningProtocol=sigv4" \
  --query 'OriginAccessControl.Id' --output text)

# ---------------------------------------------------------------------------------------------
# 4. The shared secret that proves a request came through CloudFront.
#    OriginVerificationFilter rejects anything reaching the origin without it.
# ---------------------------------------------------------------------------------------------
ORIGIN_SECRET=$(openssl rand -base64 32)
aws secretsmanager create-secret --region "$REGION" \
  --name "dev/${APP}-dev/origin-verify-secret" \
  --secret-string "{\"ORIGIN_VERIFY_SECRET\":\"${ORIGIN_SECRET}\"}"
# Then add it to infra/ecs/taskdef.json alongside JWT_SECRET, as a `secrets` entry:
#   { "name": "ORIGIN_VERIFY_SECRET", "valueFrom": "<arn>:ORIGIN_VERIFY_SECRET::" }

# ---------------------------------------------------------------------------------------------
# 5. The distribution.
#
#    Two managed policies do the heavy lifting on the /api/* behavior, and picking the wrong ones
#    is the classic way to break an API behind CloudFront:
#
#    CachePolicy CachingDisabled (4135ea2d-...) — every endpoint here is authenticated and
#      per-user. Caching them would serve one user's boards to another.
#
#    OriginRequestPolicy AllViewerExceptHostHeader (b689b0a8-...) — forwards the viewer's headers,
#      query strings and cookies to the origin, which is what keeps `Authorization: Bearer` and the
#      refresh cookie alive. Note *ExceptHostHeader*: plain AllViewer forwards the viewer's Host
#      (your custom domain) to execute-api, which does not recognize it and answers 403. That 403
#      looks exactly like an auth failure and will eat an afternoon.
# ---------------------------------------------------------------------------------------------
cat > /tmp/cf-dist.json <<JSON
{
  "CallerReference": "${APP}-$(date +%s)",
  "Aliases": { "Quantity": 1, "Items": ["${DOMAIN}"] },
  "DefaultRootObject": "index.html",
  "Origins": {
    "Quantity": 2,
    "Items": [
      {
        "Id": "s3-frontend",
        "DomainName": "${BUCKET}.s3.${REGION}.amazonaws.com",
        "OriginAccessControlId": "${OAC_ID}",
        "S3OriginConfig": { "OriginAccessIdentity": "" }
      },
      {
        "Id": "api-gateway",
        "DomainName": "${API_ORIGIN_DOMAIN}",
        "CustomOriginConfig": {
          "HTTPPort": 80,
          "HTTPSPort": 443,
          "OriginProtocolPolicy": "https-only",
          "OriginSslProtocols": { "Quantity": 1, "Items": ["TLSv1.2"] }
        },
        "CustomHeaders": {
          "Quantity": 1,
          "Items": [
            { "HeaderName": "X-Origin-Verify", "HeaderValue": "${ORIGIN_SECRET}" }
          ]
        }
      }
    ]
  },
  "DefaultCacheBehavior": {
    "TargetOriginId": "s3-frontend",
    "ViewerProtocolPolicy": "redirect-to-https",
    "CachePolicyId": "658327ea-f89d-4fab-a63d-7e88639e58f6",
    "Compress": true
  },
  "CacheBehaviors": {
    "Quantity": 1,
    "Items": [
      {
        "PathPattern": "/api/*",
        "TargetOriginId": "api-gateway",
        "ViewerProtocolPolicy": "https-only",
        "AllowedMethods": {
          "Quantity": 7,
          "Items": ["GET","HEAD","OPTIONS","PUT","POST","PATCH","DELETE"],
          "CachedMethods": { "Quantity": 2, "Items": ["GET","HEAD"] }
        },
        "CachePolicyId": "4135ea2d-6df8-44a3-9df3-4b5a84be39ad",
        "OriginRequestPolicyId": "b689b0a8-53d0-40ab-baf2-68738e2966ac",
        "Compress": true
      }
    ]
  },
  "ViewerCertificate": {
    "ACMCertificateArn": "${CERT_ARN}",
    "SSLSupportMethod": "sni-only",
    "MinimumProtocolVersion": "TLSv1.2_2021"
  },
  "WebACLId": "${WAF_ARN}",
  "Comment": "${APP}",
  "Enabled": true
}
JSON

DIST_ID=$(aws cloudfront create-distribution --distribution-config file:///tmp/cf-dist.json \
  --query 'Distribution.Id' --output text)
DIST_DOMAIN=$(aws cloudfront get-distribution --id "$DIST_ID" \
  --query 'Distribution.DomainName' --output text)

# S3 bucket policy: only this distribution may read.
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
aws s3api put-bucket-policy --bucket "$BUCKET" --policy "$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Service": "cloudfront.amazonaws.com" },
    "Action": "s3:GetObject",
    "Resource": "arn:aws:s3:::${BUCKET}/*",
    "Condition": {
      "StringEquals": {
        "AWS:SourceArn": "arn:aws:cloudfront::${ACCOUNT}:distribution/${DIST_ID}"
      }
    }
  }]
}
JSON
)"

# ---------------------------------------------------------------------------------------------
# 6. Point the domain at the distribution (Route 53 alias; the CloudFront zone id is a constant).
# ---------------------------------------------------------------------------------------------
aws route53 change-resource-record-sets --hosted-zone-id "$HOSTED_ZONE_ID" \
  --change-batch "$(cat <<JSON
{
  "Changes": [{
    "Action": "UPSERT",
    "ResourceRecordSet": {
      "Name": "${DOMAIN}",
      "Type": "A",
      "AliasTarget": {
        "HostedZoneId": "Z2FDTNDATAQYW2",
        "DNSName": "${DIST_DOMAIN}",
        "EvaluateTargetHealth": false
      }
    }
  }]
}
JSON
)"

echo "distribution: $DIST_ID ($DIST_DOMAIN)"
echo "domain:       https://${DOMAIN}"
echo "origin secret is in Secrets Manager — add it to taskdef.json and redeploy BEFORE"
echo "the filter can enforce it. Deploy order matters; see docs/aws-deployment.md §13."
