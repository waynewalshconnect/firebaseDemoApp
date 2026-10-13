# Executive Investigation Report: Firebase OIDC Sign-In

**As of:** 2026-10-13
**Audience:** Engineering leadership, identity platform owners, application/API owners

## Executive Summary

The mobile application successfully authenticates through direct MSAL and completes the backend token exchange and profile request. The optional Firebase generic OIDC path does not complete sign-in.

The confirmed blocker is the identity provider's issuer/discovery contract. Firebase rejects the provider before redirecting to the upstream identity sign-in because it cannot obtain discovery metadata whose declared issuer matches the configured issuer. The tested provider metadata variants did not provide both a matching issuer and a reachable standard discovery URL. This occurs before token exchange; the exchange API is not the cause of this Firebase sign-in error.

**Recommendation:** Keep direct MSAL as the working path. Enable Firebase OIDC only after the identity platform supplies and verifies a standards-compatible issuer/discovery contract. If that cannot be provided by the current identity service, evaluate an OIDC broker that owns a consistent issuer and metadata contract. Do not weaken issuer validation or rewrite signed tokens.

## Scope and Privacy

This report intentionally omits tenant and project names, domains, provider IDs, client IDs, user identifiers, personal claims, tokens, and credentials. The findings are based on the application behavior, Firebase Console configuration, and public discovery endpoint responses observed during this investigation. No raw token or client-secret value is included.

## Current State

- Direct MSAL sign-in is working.
- The backend exchange returns an Elevate token, and the application loads the current-user profile successfully through this path.
- A Firebase OIDC provider is enabled with authorization-code flow; a previous provider is disabled.
- The mobile app is configured to request the enabled provider.
- Firebase OIDC fails before upstream authentication completes, so no Firebase-returned upstream token is available to exchange.

## Evidence and Findings

### Confirmed Firebase error

The Firebase web widget returned an invalid-credential error stating that the issuer in the OIDC discovery document does not match the issuer configured for the request. After the issuer field was changed, the app was retried and the same issuer-mismatch class of error was observed. The app remained locked at the authentication step; exchange and profile calls were not attempted.

### Discovery behavior

The policy-specific discovery URL is reachable and returns authorization, token, logout, and signing-key endpoint metadata. Its `issuer` value is not consistently the same across the tested endpoint forms. One form declared a tenant-GUID issuer; a policy-prefixed form declared a policy-specific issuer.

When Firebase was configured with the tenant-GUID issuer, Firebase's standard discovery URL derived from that issuer returned HTTP 404. When configured with the policy-path issuer, Firebase could retrieve policy discovery, but the document declared a different issuer. The tested combinations therefore did not satisfy both Firebase requirements:

1. Discovery must be reachable at the standard well-known URL derived from the configured issuer.
2. The discovery document's `issuer` must exactly equal the configured issuer.

The token's `iss` must also match the issuer contract accepted by the application and its backend. Changing only one value does not establish compatibility.

### Failure boundary

The Firebase error occurs before B2C sign-in, callback processing, credential extraction, token exchange, or profile retrieval. Callback registration, client-secret correctness, returned token type, and backend guest mapping remain important follow-up checks, but they cannot explain the current discovery mismatch because the flow has not reached those stages.

## Root Cause

The tested identity-provider endpoints do not currently expose a self-consistent issuer/discovery contract that Firebase accepts. The policy-specific OAuth endpoints can be valid and reachable while the issuer's derived discovery endpoint is absent or declares a different issuer. These endpoint URLs are related but are not interchangeable:

- Authorization and token endpoints identify where protocol requests are sent.
- The discovery document describes those endpoints and declares the issuer.
- The issuer identifies the authority that signs the tokens.

Firebase validates the relationship between the configured issuer and discovery metadata before it begins sign-in.

## Options and Recommendation

### Option A: Continue with direct MSAL (recommended immediate path)

This is already working for sign-in, token exchange, and profile retrieval. It avoids introducing Firebase as an additional OIDC broker while the issuer contract is unresolved.

### Option B: Correct the identity-provider OIDC contract

The identity platform owner should configure a supported issuer strategy and verify the deployed result. A custom-policy JWT issuer setting can change the token issuer format, but it is only a candidate change; it is not proof that discovery metadata or the standard well-known endpoint will also match. Test the complete contract before enabling Firebase.

### Option C: Introduce an OIDC broker

If the current identity service cannot publish the required contract, evaluate a broker that owns a stable issuer, serves matching discovery/JWKS metadata, and issues tokens whose `iss` matches that issuer. A metadata-only proxy or a rewritten B2C token is not sufficient and must not be used to bypass issuer validation.

## Required Actions

1. **Identity platform:** Choose the issuer strategy and provide a discovery URL derived from that issuer that returns HTTP 200 with an exact matching `issuer` field.
2. **Identity platform:** Complete a fresh authorization-code flow and verify the returned token's `iss`, audience, subject, policy claim, expiry, and signing key. Do not distribute raw tokens.
3. **API owner:** Confirm the backend's issuer allow-list and validation profile match the selected Firebase token type. If the issuer changes, update backend configuration and tests in coordination with all affected clients.
4. **Application owner:** Confirm the Firebase callback is registered in the upstream application and that Firebase returns the intended upstream ID token or access token. Keep that choice consistent with backend validation.
5. **Security owner:** Rotate the Firebase provider client secret that was exposed during console inspection. Store the replacement only in Firebase/server-side configuration, never in the mobile application or repository.
6. **Release owner:** Keep direct MSAL available as a rollback path while Firebase is evaluated in the CI environment.

## Acceptance Criteria

Firebase OIDC should not be declared fixed until all of the following pass:

- The configured issuer, discovery document `issuer`, and fresh token `iss` are identical.
- The standard discovery URL derived from the configured issuer is reachable over HTTPS and returns valid metadata.
- The metadata's authorization, token, and JWKS endpoints are reachable and correspond to the intended CI policy.
- Firebase completes authorization-code sign-in and returns the explicitly selected upstream token type.
- The backend validates that token using the matching issuer/audience/profile and resolves the active environment-specific user mapping.
- The application completes exchange and loads the profile endpoint.
- Invalid issuer, audience, signature, expiry, and unmapped-user cases remain rejected with safe, correlatable errors.

## Risks and Controls

- Changing the issuer can affect every client and API that trusts the policy. Deploy to CI first, coordinate backend allow-lists, monitor, and preserve rollback.
- Do not weaken JWT signature, issuer, audience, expiry, or user-mapping validation to make Firebase sign-in pass.
- Never log or share raw identity tokens, refresh tokens, client secrets, authorization headers, or personal claims. Use trace IDs and categorized validation outcomes for diagnosis.
- Rotate the exposed provider secret before further production use.