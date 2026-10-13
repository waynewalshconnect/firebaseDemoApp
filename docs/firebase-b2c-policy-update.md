# B2C Policy Update for Firebase OIDC

## Purpose

Use this checklist with the owner of the CI Azure AD B2C custom policy. Firebase OIDC currently fails before sign-in because Firebase rejects the issuer returned by the policy's discovery document.

## Verified CI State

- Firebase project: `intouchtestapp`
- Firebase provider ID: `oidc.ci-firebase` (enabled)
- Firebase client ID: `249bc6ab-e363-4c94-b10d-5eb675922354`
- Firebase issuer field: `https://intouchelevateidentitydev.b2clogin.com/intouchelevateidentitydev.onmicrosoft.com/b2c_1a_ci_signup_signin/v2.0`
- Firebase callback shown by the console: `https://intouchtestapp.firebaseapp.com/__/auth/handler`
- Policy discovery URL: `https://intouchelevateidentitydev.b2clogin.com/intouchelevateidentitydev.onmicrosoft.com/b2c_1a_ci_signup_signin/v2.0/.well-known/openid-configuration`
- Discovery currently declares issuer: `https://intouchelevateidentitydev.b2clogin.com/599403e0-fde4-482d-8206-b5565ae9909e/v2.0/`
- Firebase error after setting its issuer field to the policy base URL: `` `issuer` claim in OIDC discovery document does not match the issuer specified in the request. ``

The full `.well-known/openid-configuration` URL is a metadata endpoint, not the value for Firebase's **Issuer (URL)** field. Firebase derives the metadata URL from the issuer. The field has been corrected to the policy base URL, but B2C metadata still declares a different issuer.

## Required End State

All three issuer values must be identical:

1. Firebase provider's Issuer URL (issuer identifier only; do not append `/.well-known/openid-configuration`).
2. The discovery document's `issuer` property.
3. The `iss` claim in the B2C ID token returned by the CI policy.

The standard discovery URL derived from the configured issuer must be reachable over HTTPS and return that same `issuer` value. Do not consider the change complete just because the policy-specific discovery URL returns JSON.

## Custom Policy Change

In the CI custom-policy source, locate the `JwtIssuer` technical profile, usually in `TrustFrameworkExtensions.xml` or the relying-party policy. Inspect its current `IssuanceClaimPattern`. Microsoft documents two values:

- `AuthorityAndTenantGuid` (default): issuer includes the tenant GUID.
- `AuthorityWithTfp`: issuer includes the tenant GUID and relying-party policy.

`AuthorityWithTfp` is a candidate to evaluate for a policy-specific issuer. It is **not sufficient by itself**: after deployment, verify that B2C discovery metadata also declares exactly the issuer Firebase is configured to use and that discovery is available at the standard URL derived from that issuer.

If the existing profile uses `AuthorityAndTenantGuid`, the candidate metadata override is:

```xml
<ClaimsProviders>
  <ClaimsProvider>
    <DisplayName>Token Issuer</DisplayName>
    <TechnicalProfiles>
      <TechnicalProfile Id="JwtIssuer">
        <Metadata>
          <Item Key="IssuanceClaimPattern">AuthorityWithTfp</Item>
        </Metadata>
      </TechnicalProfile>
    </TechnicalProfiles>
  </ClaimsProvider>
</ClaimsProviders>
```

Merge the override into the existing policy structure; do not replace unrelated `JwtIssuer` metadata or cryptographic keys. Deploy the CI policy and its dependencies using the team's normal policy upload order. Verify the actual resulting `iss` claim and discovery metadata before changing Firebase again.

If the discovery document continues to declare the GUID-only issuer, or the standard discovery URL under the resulting issuer is unavailable, stop. The built-in B2C endpoint is not providing the issuer/discovery contract Firebase requires. The alternatives are an identity broker that issues its own consistent OIDC tokens, or keeping the working direct-MSAL path. Do not weaken issuer validation or rewrite a signed JWT in a proxy.

## Deployment Impact

Changing the issuer affects every relying application and API that trusts tokens from this CI policy, not just Firebase. Before rollout:

- Update the CI exchange API's issuer allow-list and validation tests to accept the new exact issuer.
- Confirm the token audience remains `249bc6ab-e363-4c94-b10d-5eb675922354` and the subject/mapping remains stable.
- Confirm the policy continues to issue the required B2C ID-token claims (`sub`, expiry, and the configured policy claim).
- Register the Firebase callback URL above in the B2C app registration if it is not already registered.
- Keep the existing MSAL login and exchange working during the change; test both token profiles if the same policy serves both clients.
- Preserve a rollback to the previous policy version and issuer allow-list if other CI clients fail.

## Validation Checklist

1. Fetch the policy discovery document and record its `issuer`, `authorization_endpoint`, `token_endpoint`, and `jwks_uri`.
2. Fetch `/.well-known/openid-configuration` under the proposed issuer URL. Require HTTP 200 and an `issuer` exactly equal to the proposed value.
3. Complete a fresh Firebase OIDC sign-in and inspect only the decoded ID-token claims in a trusted local tool. Require its `iss` to equal the Firebase issuer and discovery `issuer`; do not share the raw token.
4. Confirm the ID token has the expected CI audience, subject, policy claim, and valid `exp`/`nbf` values.
5. Confirm Firebase sign-in completes and returns the expected upstream B2C token.
6. Confirm the CI exchange API validates that token and maps the subject to the active CI guest.
7. Confirm the app receives the documented Elevate token response and loads `/v1/user/me` with the Elevate access token.

Use a trace ID and safe validation categories for failures. Never log B2C tokens, Elevate tokens, refresh tokens, client secrets, authorization headers, or personal claims.

## Secret Rotation

The Firebase provider's client-secret field was exposed during console inspection. Rotate the confidential client secret in the identity provider and update the Firebase provider with the replacement before enabling production use. Keep the secret out of this repository and all client apps.

## Reference

[Microsoft: Configure tokens for Azure AD B2C custom policies](https://learn.microsoft.com/en-us/azure/active-directory-b2c/configure-tokens?pivots=b2c-custom-policy)

[Microsoft: JWT issuer technical profile](https://learn.microsoft.com/en-us/azure/active-directory-b2c/jwt-issuer-technical-profile)