package com.intouch.testapp

import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthWebException
import com.google.firebase.auth.OAuthCredential
import com.google.firebase.auth.OAuthProvider
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private enum class FlowStep { B2C, EXCHANGE, PROFILE }
private enum class AuthMode(val label: String) { MSAL("MSAL"), FIREBASE_OIDC("Firebase OIDC") }

private data class UserProfile(
    val guestId: String,
    val email: String,
    val firstName: String,
    val lastName: String,
)

private data class AppState(
    val message: String = "Ready to authenticate",
    val b2cComplete: Boolean = false,
    val exchangeComplete: Boolean = false,
    val exchangeDetail: String? = null,
    val profile: UserProfile? = null,
    val activeStep: FlowStep? = null,
    val failedStep: FlowStep? = null,
)

class MainActivity : ComponentActivity() {
    private var state by mutableStateOf(AppState())
    private var authenticated by mutableStateOf(false)
    private var authMode by mutableStateOf(AuthMode.MSAL)
    private var b2cApplication: ISingleAccountPublicClientApplication? = null
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private var b2cAccessToken: String? = null
    private var exchangeToken: String? = null
    private var elevateAccessToken: String? = null
    private var b2cProfile: UserProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pendingFirebaseSignIn = firebaseAuth.pendingAuthResult
        if (pendingFirebaseSignIn != null || firebaseAuth.currentUser != null) {
            authMode = AuthMode.FIREBASE_OIDC
            authenticated = true
        }
        setContent {
            DemoApp(
                state,
            authenticated,
                authMode,
                ::selectAuthMode,
                ::signIn,
                ::exchangeToken,
                ::loadProfile,
                ::signOut,
            )
        }
        initializeB2c()
        pendingFirebaseSignIn?.let(::observeFirebaseSignIn)
    }

    private fun selectAuthMode(mode: AuthMode) {
        if (state.activeStep == null && !state.b2cComplete) authMode = mode
    }

    private fun initializeB2c() {
        if (listOf(BuildConfig.B2C_CLIENT_ID, BuildConfig.B2C_AUTHORITY, BuildConfig.B2C_REDIRECT_URI).any(String::isBlank)) {
            state = AppState(message = "Add the B2C values to local.properties, then rebuild", failedStep = FlowStep.B2C)
            return
        }

        PublicClientApplication.createSingleAccountPublicClientApplication(
            this,
            R.raw.msal_config,
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(application: ISingleAccountPublicClientApplication) {
                    b2cApplication = application
                    application.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                        override fun onAccountLoaded(activeAccount: IAccount?) {
                            authenticated = activeAccount != null
                        }

                        override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {
                            authenticated = currentAccount != null
                        }

                        override fun onError(exception: MsalException) = Unit
                    })
                    if (authMode == AuthMode.MSAL && state.activeStep == null && !state.b2cComplete) {
                        state = AppState()
                    }
                }

                override fun onError(exception: MsalException) {
                    state = state.copy(
                        message = exception.message ?: "Unable to initialize B2C",
                        activeStep = null,
                        failedStep = FlowStep.B2C,
                    )
                }
            },
        )
    }

    private fun signIn() {
        when (authMode) {
            AuthMode.MSAL -> signInWithMsal()
            AuthMode.FIREBASE_OIDC -> signInWithFirebaseOidc()
        }
    }

    private fun signInWithMsal() {
        val application = b2cApplication ?: return
        val scopes = BuildConfig.B2C_SCOPES.split(',', ' ').filter(String::isNotBlank)
        if (scopes.isEmpty() || BuildConfig.TOKEN_EXCHANGE_URL.isBlank() || BuildConfig.USER_PROFILE_URL.isBlank()) {
            state = AppState(
                message = "Add the B2C scope, exchange URL, and profile URL to local.properties",
                failedStep = FlowStep.B2C,
            )
            return
        }

        state = AppState(message = "Signing in with Microsoft Entra B2C", activeStep = FlowStep.B2C)
        application.signIn(
            SignInParameters.builder()
                .withActivity(this)
                .withScopes(scopes)
                .withCallback(object : AuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        b2cAccessToken = authenticationResult.accessToken
                        exchangeToken = authenticationResult.accessToken
                        elevateAccessToken = null
                        b2cProfile = userProfileFromClaims(
                            authenticationResult.account.claims.orEmpty(),
                            authenticationResult.account.username,
                        )
                        authenticated = true
                        state = AppState(
                            message = "B2C authentication complete\n${decodedTokenClaims(authenticationResult.accessToken)}",
                            b2cComplete = true,
                        )
                    }

                    override fun onError(exception: MsalException) {
                        state = AppState(
                            message = exception.message ?: "B2C authentication failed",
                            failedStep = FlowStep.B2C,
                        )
                    }

                    override fun onCancel() {
                        state = AppState(message = "Sign-in cancelled")
                    }
                })
                .build(),
        )
    }

    private fun signInWithFirebaseOidc() {
        if (BuildConfig.FIREBASE_OIDC_PROVIDER_ID.isBlank()) {
            state = AppState(message = "Add the Firebase OIDC provider ID to local.properties", failedStep = FlowStep.B2C)
            return
        }
        val scopes = listOf("openid")
        val provider = OAuthProvider.newBuilder(BuildConfig.FIREBASE_OIDC_PROVIDER_ID).apply {
            this.scopes = scopes
        }.build()
        state = AppState(message = "Signing in through Firebase OIDC", activeStep = FlowStep.B2C)
        observeFirebaseSignIn(firebaseAuth.startActivityForSignInWithProvider(this, provider))
    }

    private fun observeFirebaseSignIn(task: com.google.android.gms.tasks.Task<AuthResult>) {
        task.addOnSuccessListener(::completeFirebaseSignIn)
            .addOnFailureListener { error ->
                val message = when {
                    error is FirebaseAuthWebException && error.errorCode == "ERROR_WEB_CONTEXT_CANCELED" ->
                        "Firebase OIDC sign-in was cancelled"
                    error.message?.contains("CONFIGURATION_NOT_FOUND") == true ->
                        "Firebase Authentication or provider ${BuildConfig.FIREBASE_OIDC_PROVIDER_ID} is not configured in intouchtestapp"
                    error is FirebaseAuthException ->
                        "${error.errorCode}: ${error.message ?: "Firebase OIDC authentication failed"}"
                    else -> error.message ?: "Firebase OIDC authentication failed"
                }
                state = AppState(
                    message = message,
                    failedStep = FlowStep.B2C,
                )
            }
    }

    private fun completeFirebaseSignIn(result: AuthResult) {
        val credential = result.credential as? OAuthCredential
        val idToken = credential?.idToken
        val accessToken = credential?.accessToken
        if (idToken.isNullOrBlank()) {
            state = AppState(
                message = "Firebase OIDC returned no B2C ID token",
                failedStep = FlowStep.B2C,
            )
            return
        }
        val user = result.user
        val claims = result.additionalUserInfo?.profile.orEmpty()
        b2cAccessToken = accessToken ?: idToken
        exchangeToken = idToken
        elevateAccessToken = null
        b2cProfile = userProfileFromClaims(claims, user?.email ?: user?.displayName)
        state = AppState(message = "Firebase OIDC authentication complete", b2cComplete = true)
    }

    private fun exchangeToken() {
        val accessToken = exchangeToken ?: run {
            state = state.copy(
                message = "Sign in again to refresh the B2C token before exchanging",
                failedStep = FlowStep.B2C,
            )
            return
        }
        lifecycleScope.launch {
            runCatching {
                state = state.copy(
                    message = "Exchanging the B2C token on the trusted backend",
                    exchangeComplete = false,
                    exchangeDetail = null,
                    activeStep = FlowStep.EXCHANGE,
                    failedStep = null,
                )
                TokenExchangeClient.exchange(BuildConfig.TOKEN_EXCHANGE_URL, accessToken)
            }.onSuccess { result ->
                elevateAccessToken = result.accessToken
                state = state.copy(
                    message = "Elevate token exchange complete",
                    exchangeComplete = true,
                    exchangeDetail = "${result.tokenType} token, expires in ${result.expiresIn} seconds",
                    activeStep = null,
                )
            }.onFailure { error ->
                elevateAccessToken = null
                state = state.copy(
                    message = error.message ?: "Token exchange failed",
                    activeStep = null,
                    failedStep = FlowStep.EXCHANGE,
                )
            }
        }
    }

    private fun loadProfile() {
        if (b2cAccessToken == null) {
            state = state.copy(message = "Complete B2C authentication first", failedStep = FlowStep.B2C)
            return
        }
        val profile = b2cProfile
        if (profile == null || (profile.email.isBlank() && profile.guestId.isBlank())) {
            state = state.copy(message = "B2C token contains no usable user identity", failedStep = FlowStep.PROFILE)
            return
        }
        state = state.copy(
            message = "B2C user identity verified",
            profile = profile,
            activeStep = null,
            failedStep = null,
        )
    }

    private fun signOut() {
        firebaseAuth.signOut()
        val clearLocalState = {
            b2cAccessToken = null
            exchangeToken = null
            elevateAccessToken = null
            b2cProfile = null
            authenticated = false
            state = AppState(message = "Signed out")
        }
        b2cApplication?.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() {
                clearLocalState()
            }

            override fun onError(exception: MsalException) {
                clearLocalState()
            }
        }) ?: clearLocalState()
    }
}

private fun userProfileFromClaims(claims: Map<String, *>, username: String?): UserProfile {
    fun claim(vararg names: String): String = names.firstNotNullOfOrNull { name ->
        when (val value = claims[name]) {
            is String -> value.takeIf(String::isNotBlank)
            is Collection<*> -> value.firstOrNull()?.toString()?.takeIf(String::isNotBlank)
            else -> value?.toString()?.takeIf(String::isNotBlank)
        }
    }.orEmpty()

    return UserProfile(
        guestId = claim("oid", "sub"),
        email = claim("email", "emails", "preferred_username").ifBlank { username.orEmpty() },
        firstName = claim("given_name"),
        lastName = claim("family_name"),
    )
}

private data class TokenExchangeResult(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
)

private object TokenExchangeClient {
    suspend fun exchange(endpoint: String, accessToken: String): TokenExchangeResult = withContext(Dispatchers.IO) {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.bufferedWriter().use {
                it.write(JSONObject().put("b2CToken", accessToken).toString())
            }
            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                val problem = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                error(problem?.takeIf(String::isNotBlank) ?: "Token exchange returned HTTP ${connection.responseCode}")
            }
            val response = JSONObject(body)
            check(response.optString("accessToken").isNotBlank()) { "Exchange response is missing accessToken" }
            check(response.optString("refreshToken").isNotBlank()) { "Exchange response is missing refreshToken" }
            TokenExchangeResult(
                accessToken = response.getString("accessToken"),
                tokenType = response.optString("tokenType", "Bearer"),
                expiresIn = response.optLong("expiresIn"),
            )
        } finally {
            connection.disconnect()
        }
    }
}

private object UserProfileClient {
    suspend fun getCurrentUser(endpoint: String, accessToken: String): UserProfile = withContext(Dispatchers.IO) {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")

            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                val problem = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                error(problem?.takeIf(String::isNotBlank) ?: "User profile returned HTTP ${connection.responseCode}")
            }
            val response = JSONObject(body)
            UserProfile(
                guestId = response.optString("guestId"),
                email = response.optString("email"),
                firstName = response.optString("firstName"),
                lastName = response.optString("lastName"),
            )
        } finally {
            connection.disconnect()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DemoApp(
    state: AppState,
    authenticated: Boolean,
    authMode: AuthMode,
    onAuthModeSelected: (AuthMode) -> Unit,
    onSignIn: () -> Unit,
    onExchangeToken: () -> Unit,
    onLoadProfile: () -> Unit,
    onSignOut: () -> Unit,
) {
    val busy = state.activeStep != null
    val colors = lightColorScheme(
        primary = Color(0xFF006B5B),
        surface = Color(0xFFF7F8F3),
        onSurface = Color(0xFF18201D),
        error = Color(0xFFB42318),
    )
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("INTOUCH LAB", color = colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("B2C to Elevate", fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 0.sp)
                    Text("Secure token exchange demo", color = Color(0xFF58635F), fontSize = 18.sp)
                    Spacer(Modifier.height(20.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        AuthMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = authMode == mode,
                                onClick = { onAuthModeSelected(mode) },
                                enabled = !busy && !state.b2cComplete,
                                shape = SegmentedButtonDefaults.itemShape(index, AuthMode.entries.size),
                            ) {
                                Text(mode.label)
                            }
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    val b2cStatus = when {
                        state.activeStep == FlowStep.B2C -> "Authentication in progress"
                        state.failedStep == FlowStep.B2C -> "Failed: ${state.message}"
                        state.b2cComplete -> "Authenticated; B2C access token received"
                        else -> "Ready"
                    }
                    val profileStatus = state.profile?.let { profile ->
                        val name = listOf(profile.firstName, profile.lastName)
                            .filter(String::isNotBlank)
                            .joinToString(" ")
                        listOf(
                            name.takeIf(String::isNotBlank)?.let { "Name: $it" },
                            profile.email.takeIf(String::isNotBlank)?.let { "Email: $it" },
                            profile.guestId.takeIf(String::isNotBlank)?.let { "Guest ID: $it" },
                        ).filterNotNull().joinToString("\n")
                    } ?: when {
                        state.activeStep == FlowStep.PROFILE -> "Loading profile"
                        state.failedStep == FlowStep.PROFILE -> "Failed: ${state.message}"
                        !state.exchangeComplete -> "Locked until token exchange"
                        else -> "Ready to load profile"
                    }
                    val exchangeStatus = when {
                        state.activeStep == FlowStep.EXCHANGE -> "Exchanging B2C token"
                        state.failedStep == FlowStep.EXCHANGE -> "Failed: ${state.message}"
                        state.exchangeComplete -> state.exchangeDetail ?: "Exchange complete"
                        !state.b2cComplete -> "Locked until B2C authentication"
                        else -> "Ready to exchange token"
                    }

                    StepRow("1", "B2C via ${authMode.label}", b2cStatus, state.b2cComplete || state.activeStep == FlowStep.B2C, !busy, onSignIn)
                    StepRow("2", "Elevate token exchange", exchangeStatus, state.exchangeComplete || state.activeStep == FlowStep.EXCHANGE, state.b2cComplete && !busy, onExchangeToken)
                    StepRow("3", "Current user profile", profileStatus, state.profile != null || state.activeStep == FlowStep.PROFILE, state.exchangeComplete && !busy, onLoadProfile)
                    Spacer(Modifier.height(24.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .background(Color(0xFFE8ECE7), RoundedCornerShape(6.dp)).padding(16.dp),
                    ) {
                        Column {
                            Text(
                                state.message,
                                fontWeight = FontWeight.SemiBold,
                                color = if (state.failedStep != null) colors.error else colors.onSurface,
                            )
                        }
                    }
                }

                if (authenticated) {
                    Button(
                        onClick = onSignOut,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text("Sign out", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    number: String,
    label: String,
    status: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.background(
                if (active) Color(0xFF006B5B) else Color(0xFFD8DEDA),
                RoundedCornerShape(4.dp),
            ).padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(number, color = if (active) Color.White else Color(0xFF58635F), fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(status, color = Color(0xFF58635F), fontSize = 13.sp)
        }
    }
}

private fun decodedTokenClaims(token: String): String {
    val payload = token.split('.').getOrNull(1)
        ?: return "Token claims unavailable"
    val claims = runCatching {
        val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        JSONObject(String(bytes, Charsets.UTF_8))
    }.getOrNull() ?: return "Token claims unavailable"
    val expiry = claims.optLong("exp")
    val expiryText = if (expiry > 0) java.time.Instant.ofEpochSecond(expiry).toString() else "missing"
    return listOf(
        "Token claims (decoded locally; not signature-verified):",
        "iss=${claims.optString("iss", "missing")}",
        "aud=${claims.opt("aud")?.toString() ?: "missing"}",
        "sub=${claims.optString("sub", "missing")}",
        "oid=${claims.optString("oid", "missing")}",
        "policy=${claims.optString("tfp").ifBlank { claims.optString("acr", "missing") }}",
        "scp=${claims.optString("scp", "missing")}",
        "exp=$expiryText",
    ).joinToString("\n")
}