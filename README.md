# B2C token exchange demo

Android package: `com.intouch.testapp`  
Firebase project: `intouchtestapp`

The supplied Firebase file configures the Android Firebase SDK, but contains no OAuth client or Microsoft Entra B2C settings. This app uses MSAL for B2C authentication and the NZSki integration API to exchange the B2C access token for Elevate tokens.

## Flow

1. Android signs in with Microsoft Entra B2C using MSAL and obtains an API access token.
2. Android posts `{ "b2CToken": "..." }` to the selected environment's `tokenExchange.url` over HTTPS.
3. The backend validates the token issuer, audience, signature, expiry, policy, and required claims.
4. The backend returns Elevate `accessToken`, `refreshToken`, `tokenType`, and expiry values.

The active CI operation is `POST https://apim-shared-anz-nonprod.azure-api.net/b2c-ci/v1/auth/exchange`. It does not require an APIM subscription key.

## Configure

Copy `local.properties.example` to `local.properties`, retain the local `sdk.dir`, and fill in the environment-specific settings. `app.environment=CI` is the active environment; the app reads all authentication and API values from the matching `ci.*` keys. To switch environments, set `app.environment=UAT` after filling in every `uat.*` value. The build fails with a list of missing properties rather than mixing environment settings.

Register the exact environment's `b2c.redirectUri` as an Android redirect URI in its Entra app registration. The redirect URI uses the URL-encoded signature hash; `b2c.signatureHash` uses the raw Base64 hash for Android intent matching. Environment changes take effect on the next Gradle build/install.

The CI setup uses one B2C registration for both the Android client and its own API. Per Azure AD B2C's own-service token convention, `ci.b2c.scopes` is the application client ID (`249bc6ab-e363-4c94-b10d-5eb675922354`). The configured debug redirect URI is tied to the current machine's `~/.android/debug.keystore`; release builds require their release certificate hash and a separately registered redirect URI.

The exchange endpoint must verify the B2C access token. The app validates the response fields but does not display or log either returned token.

## Firebase generic OIDC mode

Firebase OIDC requires **Authentication with Identity Platform**. In Firebase project `intouchtestapp`, add an OpenID Connect provider with provider ID `oidc.ci-firebase`, client ID `249bc6ab-e363-4c94-b10d-5eb675922354`, and issuer `https://intouchelevateidentitydev.b2clogin.com/intouchelevateidentitydev.onmicrosoft.com/b2c_1a_ci_signup_signin/v2.0`. Prefer authorization code flow; store its client secret only in Firebase Console, never in this Android project. The provider must return the B2C access token because the exchange API consumes it.

Add this web redirect URI to the B2C-CI integration app registration in the B2C tenant:

```text
https://intouchtestapp.firebaseapp.com/__/auth/handler
```

The app requests scope `249bc6ab-e363-4c94-b10d-5eb675922354`, which is the existing same-registration B2C resource scope. If the Firebase provider ID differs, update `ci.firebase.oidcProviderId` or `uat.firebase.oidcProviderId` for the selected environment.

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.