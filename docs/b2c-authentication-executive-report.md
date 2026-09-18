# B2C Authentication Executive Report

## Executive Summary

For this B2C Android app, the recommended production pattern is to authenticate directly with Microsoft Entra External ID / Azure AD B2C through MSAL and keep the trusted exchange API responsible for validating the token and issuing Elevate tokens. This minimizes trust boundaries, reduces moving parts, and avoids Firebase-specific issuer and discovery mismatches.

Firebase generic OIDC can only be used if the B2C custom policy behaves as a clean OIDC provider: the discovery document `issuer` must exactly match the token `iss`, the discovery metadata must match the live policy tenant/path, and the policy must expose a stable OIDC ID-token contract with consistent `tfp` or policy claims. When these values differ, Firebase rejects the flow with a web-widget invalid-credentials error.

This issue is not a mobile-only bug; it is a provider-contract mismatch between the B2C policy metadata and the identity broker expectations. The same root cause applies to any direct Firebase OIDC integration with this policy configuration.

**Decision:** proceed with direct MSAL for production and use Firebase only when a clear business requirement demands Firebase-backed identity. If Firebase remains required, the custom policy must be redesigned to emit a single, consistent OIDC issuer contract, or a dedicated identity broker must be introduced.

**Risk:** using a mismatched issuer/discovery configuration exposes a failed login path, inconsistent token validation, and uncertain guest mapping. That creates customer-facing sign-in failure and increases backend validation and support complexity.

**Immediate action:** keep the MSAL path as the default release path, validate token issuer/audience/policy on the exchange API, and require a single consistent issuer contract before enabling any Firebase OIDC provider.

## Required Custom Policy Notes for Firebase OIDC

Firebase generic OIDC is only viable if the custom B2C policy is designed to behave like a genuine OIDC provider, with matching issuer metadata and token claims. The main requirement is that the `issuer` in the discovery document must exactly match the value Firebase configures for the provider; otherwise Firebase rejects the response as invalid credentials.

The policy must also ensure that the token contains the expected OIDC claims and does not depend on a tenant string mismatch or an alternate issuer form.

### `tfp` / policy requirement

The custom policy should publish a stable issuer and parse the policy in a way that keeps the token contract consistent across token issuance and discovery metadata. In practice:

- Firebase expects the provider issuer to align with the discovery document exactly.
- The policy must return the same `iss` in the ID token as the discovery document `issuer` field.
- The policy must ensure the token is issued under the configured B2C policy and contains the expected policy claim (`tfp` or equivalent) if the consumer expects a policy-specific assertion.
- If the app and backend specifically validate a policy, the policy value must be consistent across the discovery metadata, token issuance, and validation rules.
- For Firebase generic OIDC, the policy must behave as a standard OIDC provider, not as a custom non-standard token flow with a different issuer shape.

The following examples are the failure pattern that breaks Firebase:

```text
Discovery issuer: https://<b2c-issuer-base>/v2.0/
Firebase provider issuer: https://<b2c-issuer-base>/<tenant-name>/<policy-name>/v2.0
```

These values do not match, and Firebase rejects the result with a web-widget credential error. Firebase generic OIDC requires a single, consistent issuer contract.

### Minimum B2C custom-policy contract for Firebase compatibility

If Firebase must be used, the custom policy must meet all of the following:

- The discovery document `issuer` must exactly match the token `iss` claim.
- The discovery URL must be reachable and published under the same issuer path and tenant used by the token.
- The policy must issue an ID token in OIDC format, not just an access token or an internal custom token.
- The token must include the standard OIDC claims required by the provider and the API.
- If the policy is differentiating users by policy name, the claim must be exposed consistently in the token and validation rules.
- Any `tfp` claim used by the app or backend must be documented and validated explicitly.
- The custom policy must not rely on a tenant-level issuer alias that differs from the discovery document issuer.

If this contract cannot be guaranteed, Firebase should not be used for the OIDC path. The practical alternative is direct MSAL or a custom identity broker that generates a single consistent issuer for Firebase.

## Decision at a Glance

| Decision factor | Direct MSAL | Firebase generic OIDC |
|---|---|---|
| Identity path | Android -> B2C | Android -> Firebase -> B2C |
| Trust boundaries | Fewer | More |
| Best fit | Entra/B2C-native mobile apps | Firebase-centered products |
| Token ownership | Clearer | Requires explicit upstream-token contract |
| Client secret | Not used by Android | Stored in Firebase, never Android |
| Redirects | Native Android redirect | Firebase web callback plus B2C redirect |
| Operational complexity | Lower | Higher |
| Main risk | Wrong token audience/type | Provider, secret, callback, and token-type mismatch |
| Default recommendation | **Preferred** | Use only with a clear Firebase requirement |

## Approach 1: Direct MSAL

### Operating model

```text
Android --authorization code + PKCE--> B2C policy
Android --B2C token over HTTPS-----> trusted exchange API
Exchange API --validated identity----> Elevate tokens
```

MSAL manages the browser interaction, authorization-code/PKCE flow, token cache, account state, and sign-out. The Android app remains a public client. It must not contain a client secret.

### Advantages

- Direct connection between the Android client and B2C.
- Fewer external services and configuration surfaces.
- Clearer distinction between B2C tokens and Elevate tokens.
- Native account-cache and sign-out support.
- Easier backend validation model for an Entra-native API.
- Lower latency and fewer points of failure than a brokered flow.

### Security concerns

- Android is observable and must be treated as an untrusted public client.
- Never embed a client secret, API secret, signing key, or Elevate secret in the APK.
- Register exact Android redirect URIs and signing-certificate hashes.
- Maintain separate debug, test, staging, and release registrations where appropriate.
- Validate the B2C token on the exchange API. A successful MSAL callback is not authorization.
- Validate exact issuer, audience, signature, expiry, not-before, policy, subject, and scope claims.
- Do not use a token issued for one API against another API.
- Do not log or display access tokens, ID tokens, refresh tokens, authorization codes, or Authorization headers.
- Clear the MSAL account and all in-memory tokens on sign-out.
- Treat an account restored from the MSAL cache as different from a currently available access token. Acquire a fresh token before API use when necessary.

### Token-specific risk

ID tokens authenticate a user to a client. Access tokens authorize a resource API. They are not interchangeable.

The exchange API must document whether MSAL sends:

- A B2C access token for an API audience; or
- A B2C ID token under an explicit exchange contract.

The Android implementation and backend validator must use the same contract.

## Approach 2: Firebase Generic OIDC

### Operating model

```text
Android -> Firebase Authentication -> B2C OIDC provider
Android -> Firebase callback/result
Android --documented upstream token--> trusted exchange API
Exchange API --validated identity----> Elevate tokens
```

Firebase is an authentication intermediary. It stores the provider configuration and, for authorization-code flow, the confidential client secret. The Android application uses Firebase Auth APIs rather than connecting directly to B2C through MSAL.

### Advantages

- Integrates naturally with Firebase user accounts and Firebase services.
- Can provide a shared identity abstraction across platforms.
- Keeps the OIDC client secret out of the Android application.
- Firebase manages the browser/custom-tab and pending-auth-result lifecycle.
- Useful when Firebase security rules or Firebase-managed resources are part of the product architecture.

### Security concerns

- Firebase adds an additional trust boundary and operational dependency.
- Firebase provider ID, issuer, client ID, secret, scopes, callback domain, and B2C registration must all agree.
- Store the client secret only in Firebase Console or an approved server-side secret manager.
- Use authorization-code flow; avoid implicit flow for new deployments.
- Register the exact Firebase callback in B2C:

```text
https://<firebase-project-id>.firebaseapp.com/__/auth/handler
```

- Firebase may return a Firebase credential, upstream B2C ID token, upstream B2C access token, or a combination depending on configuration.
- Never assume `OAuthCredential.idToken` and `OAuthCredential.accessToken` are equivalent.
- The exchange API must explicitly define which upstream token is accepted.
- Firebase sign-in success does not prove the returned token is valid for the Elevate API.
- Protect Firebase authorized domains and review them before release.
- Handle pending auth results after activity recreation.
- Clear Firebase state and in-memory credentials on sign-out.
- Generic web errors can conceal incorrect secrets, issuers, callback URLs, or provider settings, increasing diagnosis time.

### Token-specific risk

A browser harness may post the upstream B2C ID token:

```json
{
  "b2CToken": "<B2C ID token>"
}
```

A Firebase Android implementation may instead post the upstream access token. Both can be valid credentials, but they have different claims and intended audiences. The exchange API must either:

- Accept one documented token type consistently; or
- Validate both token profiles using separate rules.

Do not solve a token mismatch by weakening validation.

## Industry Best Practices

### Identity and protocol

- Use OAuth 2.0 authorization-code flow with PKCE for Android.
- Treat Android as a public client with no embedded secret.
- Use a policy-specific issuer and discovery document for each environment.
- Use exact redirect URIs; do not use broad wildcards.
- Keep QA, CI, UAT, and production tenants, policies, audiences, APIs, and mappings separate.
- Use platform-supported identity libraries rather than hand-implementing OAuth redirects or token exchange.
- For Firebase compatibility, ensure the custom B2C policy’s discovery document `issuer` and the token `iss` claim are identical. This must be true before enabling the provider in Firebase.
- If the policy uses `tfp`, ensure the app and backend validate it consistently and do not use a different issuer form for the same policy.

### Token and API security

- Validate JWT signatures using the issuer's rotating JWKS keys.
- Validate `iss`, `aud`, `exp`, `nbf`, and the expected token type.
- Require `acr`/`tfp` only where the token profile and policy contract require it. Do not require ID-token-only claims from an access token.
- Validate scopes for API access tokens where delegated API scopes are used.
- Use a stable subject/object identifier for account mapping; use email only as a controlled fallback.
- Never accept `guestId` from the mobile request. Derive the Elevate guest mapping server-side.
- Issue Elevate tokens only after B2C validation and guest authorization succeed.
- Return generic client errors with a correlation ID and keep detailed reasons in protected server logs.

### Android security

- Use HTTPS and platform certificate validation.
- Do not log tokens or place them in analytics, crash reports, clipboard, screenshots, or support bundles.
- Keep token lifetime short and rely on approved library caches rather than custom plaintext persistence.
- Clear account and application token state on sign-out.
- Do not make authorization decisions from locally decoded JWT claims. Local decoding is diagnostic only.
- Ensure generated configuration is invalidated when environment properties change.
- Test account restoration, cancellation, process death, sign-out, and switching between authentication modes.

### Secrets and operations

- Store client secrets only in server-side configuration or managed secret storage.
- Rotate secrets before expiry and maintain an owner and rollback procedure.
- Monitor failed token validations, unusual exchange volume, repeated subject failures, and guest-mapping misses.
- Never expose raw tokens in logs, error responses, or support diagnostics.
- Add automated tests for every environment and both accepted token profiles.

## Recommended Target Architecture

For this application, the recommended production architecture is:

```text
Android + MSAL
        |
        | B2C access token over HTTPS
        v
B2C-aware exchange API
        |
        | server-side guest mapping and token exchange
        v
Elevate APIs
```

The exchange API should support both modes only if the business requires both:

```text
ID token profile: validate B2C ID-token contract
Access token profile: validate B2C API-token contract
Common identity mapping: subject/object ID -> Elevate guest
Common output: Elevate access and refresh tokens
```

The validator must not identify a token as valid merely because it contains familiar claims. It must validate the signature and complete profile-specific contract first.

## Release Gate

A release should not proceed until all of the following pass in every target environment:

- Direct MSAL login completes with a fresh account.
- Firebase OIDC login completes if Firebase is enabled for that release.
- The exact token type sent by each client is documented.
- Exchange succeeds with the expected issuer, audience, policy, and subject.
- The subject resolves to an active Elevate guest mapping.
- Profile retrieval succeeds with the returned Elevate token.
- Refresh succeeds and follows token-rotation policy.
- Sign-out clears both provider and application state.
- Invalid issuer, audience, signature, expiry, policy, scope, and mapping tests fail safely.
- Logs contain correlation IDs and safe metadata only.

## Final Recommendation

For B2C customers using Android and protected APIs, choose **direct MSAL with a server-side exchange API** as the default industry-aligned approach.

Choose Firebase generic OIDC only when Firebase capabilities justify the additional intermediary and configuration complexity. If both are supported, maintain separate, explicit token contracts and validation profiles. Never assume that a token returned through Firebase is equivalent to a token returned directly by MSAL.
