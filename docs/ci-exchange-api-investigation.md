# CI Token Exchange API Investigation

## Goal

Diagnose why the Android app receives HTTP 401 from the CI token-exchange operation while the test harness reports a successful exchange. The Android app is configured for CI and sends an MSAL access token. Do not weaken token validation to make the request pass.

## Known Client Configuration

- Exchange: `POST https://apim-shared-anz-nonprod.azure-api.net/b2c-ci/v1/auth/exchange`
- Profile: `GET https://apim-shared-anz-nonprod.azure-api.net/b2c-ci/v1/user/me`
- Android auth mode: MSAL
- CI client/audience: `249bc6ab-e363-4c94-b10d-5eb675922354`
- Configured CI policy: `b2c_1a_ci_signup_signin`
- Request content type: `application/json`
- Request body: `{ "b2CToken": "<B2C token>" }`

The app reports this generic response detail with HTTP 401:

```text
B2C token validation failed or guest mapping not found
```

## Claim Comparison

The Android app's locally decoded MSAL access-token claims and the successful harness claims report the same:

- `iss`: `https://intouchelevateidentitydev.b2clogin.com/599403e0-fde4-482d-8206-b5565ae9909e/v2.0/`
- `aud`: `249bc6ab-e363-4c94-b10d-5eb675922354`
- `sub`: present and reported as the same test subject; compare the exact value in secured tooling, not in tickets or chat

The app access token reports no `acr`/`tfp` policy claim and no `scp` claim. The harness claim sample includes `acr=b2c_1a_ci_signup_signin`. Its sample `exp` was still in the future when checked. These are decoded claims, not proof of signature validity; only the backend can validate the JWT signature and token profile.

The difference in `acr`/`scp` suggests the requests may use different token profiles. Confirm which exact token the successful harness sent. Do not assume the displayed harness claims are from the same token used for its exchange.

## Investigation Checklist

### 1. Identify the failing request

- Find the request in APIM and backend logs using its timestamp and correlation/request ID.
- Confirm it reached the expected `b2c-ci` operation and was not rejected by an APIM policy before reaching the exchange service.
- Record the HTTP status, response source, operation ID, and safe failure category.
- If no correlation ID is returned, add one to the response and log it consistently across APIM and the backend.

### 2. Compare token profiles

- Confirm whether the successful harness submitted a B2C ID token or access token.
- Confirm the Android request submitted an MSAL access token. The app sends the result's `accessToken` as `b2CToken`.
- Validate the MSAL access-token profile explicitly. Per the client contract, absence of `acr`/`tfp` or `scp` must not by itself reject an otherwise valid configured resource token.
- Do not accept an ID token as an access token by merely disabling checks. If both token types are supported, give each a documented validation profile.

### 3. Verify JWT validation configuration

- Fetch the CI policy's OIDC discovery document from the configured B2C tenant/policy and use its `issuer` and `jwks_uri` as the source of truth.
- Compare the discovery `issuer` exactly with the token `iss`. The token's issuer uses the tenant GUID path; do not construct an expected issuer from the authority URL's `/tfp/<tenant-domain>/<policy>/` path without checking discovery metadata.
- Verify signature, signing-key `kid`, key rotation, `exp`, and `nbf` with the configured clock skew.
- Confirm `aud` matches the CI application/resource expected by the exchange contract.
- Record which individual validation check failed. Never log or return the JWT itself.

### 4. Verify CI guest mapping

- Look up the validated `(environment=CI, issuer, audience, sub)` identity.
- Confirm the mapping is active and resolves to the expected application guest.
- Check for duplicate, stale, disabled, or environment-mismatched mappings.
- Since the harness reportedly succeeds with the same subject, compare its mapped identity with the identity resolved for the Android request. Do not use email as the primary mapping key when `sub` is present.

### 5. Confirm request and response contracts

- Confirm the backend receives JSON with the exact key `b2CToken` and the token value intact; never include that value in logs.
- Confirm a successful wire response contains top-level `accessToken`, `refreshToken`, `tokenType`, and `expiresIn` fields. A harness display of decoded `accessTokenClaims` may be a presentation view, not the actual HTTP response.
- Once exchange succeeds, confirm the app calls the configured CI profile endpoint with `Authorization: Bearer <Elevate access token>`.

## Safe Diagnostic Record

For each request, log only:

```text
traceId / APIM request ID
operation and environment
configured issuer name or hash
validated token profile: access_token or id_token
audience
subject hash or internal mapping key
signature / issuer / audience / time validation outcomes
guest-mapping outcome and mapping environment/status
failure category
```

Do not log the raw B2C token, Elevate tokens, refresh token, authorization headers, email, phone number, or full subject in shared diagnostics.

## Acceptance Criteria

- A valid CI MSAL access token with the configured issuer, audience, future expiry, and subject is accepted without requiring `acr`/`tfp` or `scp` when those claims are absent from this token profile.
- An active CI mapping for that validated identity is found and the API returns the documented Elevate token response.
- The app can then load the profile using the Elevate access token.
- Invalid signature, issuer, audience, expiry, or missing/disabled mapping is still rejected with a safe error and a correlatable trace ID.