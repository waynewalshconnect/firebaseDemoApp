# B2C Token Validation and Guest Mapping

## Purpose

The Elevate Integration API must support both client paths used by the application:

1. Direct Android MSAL, which sends a B2C access token.
2. The browser/Firebase OIDC path, which sends the upstream B2C ID token.

Both valid tokens identify the same B2C user and must resolve to the same Elevate guest record.

The current test user has this identity information:

```text
B2C sub/subject: <test-user-subject>
Email: <test-user@example.com>
Elevate guestId: <elevate-guest-id>
```

Do not use the Elevate `guestId` as an input supplied by the mobile client. The exchange service must derive the guest mapping from validated B2C claims.

## Current Request Contract

The Android and browser clients call:

```http
POST https://<integration-api-host>/v1/auth/exchange
Content-Type: application/json
```

Request body:

```json
{
  "b2CToken": "<B2C JWT>"
}
```

The service must not trust a guest ID, email, audience, issuer, or policy value supplied in a separate request field. Those values must come from the validated JWT.

A successful response has the existing Elevate token contract:

```json
{
  "accessToken": "<Elevate access token>",
  "refreshToken": "<Elevate refresh token>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshExpiresIn": 2592000
}
```

The client uses the returned Elevate access token for:

```http
GET /v1/user/me
Authorization: Bearer <Elevate access token>
```

## Accepted Token Types

### MSAL access token

The Android MSAL path currently sends an access token with the expected values:

```text
iss: https://<b2c-issuer-base>/v2.0/
aud: <b2c-client-id>
sub: <test-user-subject>
```

The access token may not contain `acr`, `tfp`, or `scp`. Their absence must not automatically cause rejection if the token is otherwise valid for the configured resource.

### B2C ID token

The working browser harness sends the B2C ID token. Its claims include:

```text
iss: https://<b2c-issuer-base>/v2.0/
aud: <b2c-client-id>
sub: <test-user-subject>
acr: <b2c-policy-name>
email: <test-user@example.com>
```

The ID token may contain `acr` but does not need an API `scp` claim. It is accepted because the exchange contract explicitly supports the B2C ID-token path.

## Validation Algorithm

Implement the exchange service in this order:

1. Read `b2CToken` from the JSON body.
2. Reject an empty value with HTTP `400`.
3. Parse the JWT header and claims only to select a validation profile. Do not trust the claims yet.
4. Select the CI B2C issuer configuration:
   - Tenant: `<b2c-tenant-name>`
   - Policy: `<b2c-policy-name>`
   - Discovery metadata: the policy's OIDC discovery document
   - JWKS URI: the `jwks_uri` from that document
5. Validate the JWT signature using the current JWKS keys and allow normal signing-key rotation.
6. Validate these required claims for both token types:
   - `iss` equals the configured canonical issuer.
   - `aud` equals `<b2c-client-id>`.
   - `exp` is in the future.
   - `nbf`, when present, is not in the future beyond the configured clock-skew allowance.
   - `sub` is present and non-empty.
7. Determine the token profile:
   - ID token: `acr` or `tfp` is present and the token is accepted under the documented B2C ID-token contract.
   - Access token: the token is accepted under the documented MSAL resource-token contract. Do not require `acr` or `tfp`.
8. Extract the stable identity:
   - Primary key: `sub`.
   - Optional B2C object identifier: `oid`.
   - Email only as a controlled fallback, never as the sole authorization proof when `sub` is available.
9. Look up the application guest mapping using the validated identity.
10. Reject the request if no active mapping exists.
11. Issue Elevate access and refresh tokens only after all validation and mapping checks pass.
12. Record an audit event without recording the raw token.

## Guest Mapping

Use a mapping table or equivalent record with fields similar to:

```text
environment
issuer
client_audience
b2c_subject
b2c_object_id
normalized_email
elevate_guest_id
status
created_at
updated_at
```

For the known CI test user, the mapping should include:

```text
environment: CI
issuer: https://<b2c-issuer-base>/v2.0/
client_audience: <b2c-client-id>
b2c_subject: <test-user-subject>
normalized_email: <test-user@example.com>
elevate_guest_id: <elevate-guest-id>
status: active
```

The `sub` value is the preferred mapping key. If the B2C policy can produce a different `sub` for the same human user, the identity-management process must explicitly maintain the mapping. Do not silently map a new subject by email without an approved account-linking policy.

## Error Handling

The public response may remain generic to avoid account enumeration:

```json
{
  "detail": "B2C token validation failed or guest mapping not found",
  "traceId": "<correlation ID>"
}
```

Recommended statuses:

- `400`: malformed or missing request body/token.
- `401`: invalid signature, issuer, audience, expiry, token type, or required claims.
- `403`: valid token but caller is not permitted for this application/environment.
- `404`: valid identity but no active guest mapping.
- `500`: unexpected server failure.

Server logs must distinguish the actual cause while never logging the JWT. Log only safe metadata:

```text
traceId
endpoint
issuer hash or configured issuer name
audience
token profile: id_token or access_token
subject hash or internal mapping key
guest mapping result
failure category
policy configuration version
```

Return the `traceId` to the client and use it to correlate APIM and backend logs.

## Security Requirements

- Never log the raw B2C token, Elevate token, refresh token, authorization code, or Authorization header.
- Never accept `guestId` from the client request.
- Never use decoded client-side claims as an authorization decision.
- Validate against the exact CI issuer and audience; do not accept arbitrary tenant issuers.
- Use the policy discovery document and JWKS endpoint for signature validation.
- Cache discovery metadata and signing keys with refresh-on-unknown-key behavior.
- Apply a small clock-skew allowance consistently.
- Rate-limit failed exchange attempts and monitor repeated failures.
- Keep QA/CI/UAT/production issuer, audience, endpoint, and mappings separate.
- Rotate any confidential client secrets independently of mobile client releases.
- Use HTTPS and normal certificate validation for all API calls.

## Tests Required

### Token validation tests

- Valid MSAL access token with the expected issuer, audience, subject, and expiry succeeds.
- Valid B2C ID token with the expected issuer, audience, subject, policy, and expiry succeeds.
- Wrong issuer fails with `401`.
- Wrong audience fails with `401`.
- Expired token fails with `401`.
- Invalid signature fails with `401`.
- Missing `sub` fails with `401`.
- MSAL access token without `acr` or `tfp` succeeds when all access-token requirements pass.
- ID token without the required policy claim fails if the deployment requires policy separation.

### Mapping tests

- Subject `<test-user-subject>` maps to Elevate guest `<elevate-guest-id>`.
- A valid token with no mapping returns `404` or the approved generic mapping error.
- A disabled mapping cannot exchange tokens.
- A mapping from another environment cannot be used in CI.
- Email changes do not break a subject-based mapping.

### End-to-end tests

- Browser harness signs in and exchanges its B2C ID token successfully.
- Android MSAL signs in and exchanges its B2C access token successfully.
- Android Firebase OIDC signs in and exchanges its upstream B2C ID token successfully, if that mode remains supported.
- Returned Elevate access token calls `/v1/user/me` successfully.
- Refresh token flow succeeds and rotates refresh tokens according to policy.
- Sign-out clears the client session and does not affect server-side audit history.

## Implementation Acceptance Criteria

The implementation is complete when:

- The same CI user succeeds through both MSAL and the browser/Firebase path.
- Both token types are validated by the backend with the correct profile-specific rules.
- Both paths resolve to the configured Elevate guest mapping without accepting a client-supplied guest ID.
- Invalid issuer, audience, signature, expiry, subject, policy, and mapping cases are covered by automated tests.
- APIM and backend logs can correlate failures without exposing token contents.
