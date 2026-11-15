package ke.tafiti.pumppay

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/*
  Accounts.

  Sign-up and sign-in go to Firebase Authentication (email and password) in the same
  project the controllers report to. The app holds no secrets: the key below is the
  project's public client key, which only identifies the project. Passwords are sent
  to Google over TLS and are never stored here. What the phone keeps is the signed-in
  email and name, and a refresh token that lets it stay signed in.

  Nothing about the controller depends on being signed in, so there is a way to carry on
  without an account: a pump at a farm with no signal must still be usable.
*/

/** Whether the sign-in screen is showing. Read by the activity, written by sign-in/out. */
object Session {
    var needsAuth by mutableStateOf(false)

    fun start(prefs: Prefs) { needsAuth = prefs.authRefresh.isBlank() }

    fun signOut(prefs: Prefs) {
        prefs.authEmail = ""; prefs.authName = ""; prefs.authRefresh = ""; prefs.authUid = ""
        needsAuth = true
    }
}

/** The project's Google "web" OAuth client. Public by design: it only says which project Google is signing people into. */
private const val WEB_CLIENT_ID = "248275197749-blqags8pk3rto8d1uq1bbb42frhl02aa.apps.googleusercontent.com"

object Accounts {
    private const val KEY = "AIzaSyDNuE75-FCCLjEASmre5C8eM9Tn3H6DoOw"
    private const val BASE = "https://identitytoolkit.googleapis.com/v1/accounts:"

    class Result(val ok: Boolean, val error: String = "", val email: String = "", val name: String = "", val refresh: String = "", val uid: String = "")

    private fun post(method: String, body: JSONObject): JSONObject {
        val c = URL("$BASE$method?key=$KEY").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 20_000
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        } finally { c.disconnect() }
    }

    private fun friendly(j: JSONObject): String {
        val m = j.optJSONObject("error")?.optString("message", "") ?: ""
        return when {
            m.startsWith("EMAIL_EXISTS") -> "An account with this email already exists. Log in instead."
            m.startsWith("INVALID_LOGIN_CREDENTIALS") || m.startsWith("INVALID_PASSWORD") || m.startsWith("EMAIL_NOT_FOUND") -> "Wrong email or password."
            m.startsWith("WEAK_PASSWORD") -> "Choose a password of at least 6 characters."
            m.startsWith("INVALID_EMAIL") -> "That email address does not look right."
            m.startsWith("TOO_MANY_ATTEMPTS") -> "Too many attempts. Wait a few minutes and try again."
            m.startsWith("USER_DISABLED") -> "This account has been disabled."
            m.isBlank() -> "Could not reach the sign-in service. Check your connection."
            else -> "Sign-in failed ($m)."
        }
    }

    private suspend fun safely(block: () -> Result): Result = withContext(Dispatchers.IO) {
        try { block() } catch (e: Exception) { Result(false, "No connection. Check your internet and try again.") }
    }

    suspend fun signUp(name: String, email: String, password: String): Result = safely {
        val j = post("signUp", JSONObject().put("email", email).put("password", password).put("returnSecureToken", true))
        if (j.has("error") || !j.has("idToken")) return@safely Result(false, friendly(j))
        // Keep the name with the account so a new phone shows it too.
        if (name.isNotBlank()) {
            try { post("update", JSONObject().put("idToken", j.getString("idToken")).put("displayName", name).put("returnSecureToken", false)) } catch (_: Exception) {}
        }
        Result(true, email = j.optString("email", email), name = name, refresh = j.optString("refreshToken"), uid = j.optString("localId"))
    }

    /** Trade the ID token Google gave the phone for a PumpPay (Firebase) sign-in. */
    suspend fun signInWithGoogle(googleIdToken: String): Result = safely {
        val j = post("signInWithIdp", JSONObject()
            .put("postBody", "id_token=" + java.net.URLEncoder.encode(googleIdToken, "UTF-8") + "&providerId=google.com")
            .put("requestUri", "http://localhost")
            .put("returnSecureToken", true)
            .put("returnIdpCredential", true))
        if (j.has("error") || !j.has("idToken")) return@safely Result(false, "Google sign-in did not go through. Try again, or use your email.")
        Result(true, email = j.optString("email"), name = j.optString("displayName", ""), refresh = j.optString("refreshToken"), uid = j.optString("localId"))
    }

    suspend fun signIn(email: String, password: String): Result = safely {
        val j = post("signInWithPassword", JSONObject().put("email", email).put("password", password).put("returnSecureToken", true))
        if (j.has("error") || !j.has("idToken")) return@safely Result(false, friendly(j))
        Result(true, email = j.optString("email", email), name = j.optString("displayName", ""), refresh = j.optString("refreshToken"), uid = j.optString("localId"))
    }

    private var idToken = ""
    private var idExpires = 0L

    /** A fresh ID token for the signed-in user, from the stored refresh token. */
    suspend fun idToken(prefs: Prefs): String? = withContext(Dispatchers.IO) {
        if (idToken.isNotEmpty() && System.currentTimeMillis() < idExpires - 60_000) return@withContext idToken
        if (prefs.authRefresh.isBlank()) return@withContext null
        try {
            val c = URL("https://securetoken.googleapis.com/v1/token?key=$KEY").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 20_000
                c.doOutput = true; c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(("grant_type=refresh_token&refresh_token=" + java.net.URLEncoder.encode(prefs.authRefresh, "UTF-8")).toByteArray()) }
                if (c.responseCode !in 200..299) return@withContext null
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                idToken = j.optString("id_token")
                idExpires = System.currentTimeMillis() + j.optString("expires_in", "3600").toLong() * 1000
                if (j.optString("refresh_token").isNotBlank()) prefs.authRefresh = j.optString("refresh_token")
                idToken.ifEmpty { null }
            } finally { c.disconnect() }
        } catch (e: Exception) { null }
    }

    suspend fun reset(email: String): Result = safely {
        val j = post("sendOobCode", JSONObject().put("requestType", "PASSWORD_RESET").put("email", email))
        if (j.has("error")) Result(false, friendly(j)) else Result(true)
    }
}

@Composable
fun AuthScreen(prefs: Prefs) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var signingUp by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var good by remember { mutableStateOf(false) }

    val emailOk = email.trim().contains("@") && email.trim().contains(".")
    val ready = emailOk && password.length >= 6 && (!signingUp || (name.isNotBlank() && confirm == password))

    fun finish(r: Accounts.Result, fallbackName: String) {
        prefs.authEmail = r.email
        prefs.authName = r.name.ifBlank { fallbackName }
        prefs.authRefresh = r.refresh
        prefs.authUid = r.uid
        if (prefs.ownerName.isBlank()) prefs.ownerName = prefs.authName
        Session.needsAuth = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(painterResource(R.drawable.ic_logo), null, Modifier.size(68.dp))
            Spacer(Modifier.height(18.dp))
            Text(if (signingUp) "Create your account" else "Welcome back", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (signingUp) "Sign up to keep your PumpPay details with you." else "Log in to your PumpPay account.",
                color = C.Dim, fontSize = 14.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))

            if (signingUp) {
                Field(name, { name = it; message = "" }, "Your name", enabled = !busy)
                Spacer(Modifier.height(12.dp))
            }
            Field(email, { email = it; message = "" }, "Email", enabled = !busy, keyboard = androidx.compose.ui.text.input.KeyboardType.Email)
            Spacer(Modifier.height(12.dp))
            Field(password, { password = it; message = "" }, "Password (6 or more characters)", enabled = !busy, secret = true)
            if (signingUp) {
                Spacer(Modifier.height(12.dp))
                Field(confirm, { confirm = it; message = "" }, "Confirm password", enabled = !busy, secret = true)
                if (confirm.isNotEmpty() && confirm != password) {
                    Spacer(Modifier.height(6.dp))
                    Text("The passwords do not match.", color = Color(0xFFF5B301), fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
                }
            }

            if (message.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(message, color = if (good) C.Pump else Color(0xFFF5B301), fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(22.dp))
            PrimaryButton(if (busy) "Please wait..." else if (signingUp) "Create account" else "Log in", ready && !busy) {
                busy = true; message = ""; good = false
                scope.launch {
                    val r = if (signingUp) Accounts.signUp(name.trim(), email.trim(), password)
                    else Accounts.signIn(email.trim(), password)
                    busy = false
                    if (r.ok) finish(r, name.trim()) else message = r.error
                }
            }

            // ---- Google ----
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(C.Line))
                Text("  or  ", color = C.Dim, fontSize = 12.sp)
                Box(Modifier.weight(1f).height(1.dp).background(C.Line))
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF3A3F46), RoundedCornerShape(12.dp))
                    .clickable(enabled = !busy) {
                        busy = true; message = ""; good = false
                        scope.launch {
                            try {
                                val cm = androidx.credentials.CredentialManager.create(context)
                                val option = com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID).build()
                                val request = androidx.credentials.GetCredentialRequest.Builder().addCredentialOption(option).build()
                                val cred = cm.getCredential(context, request).credential
                                if (cred is androidx.credentials.CustomCredential &&
                                    cred.type == com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                ) {
                                    val g = com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.createFrom(cred.data)
                                    val r = Accounts.signInWithGoogle(g.idToken)
                                    if (r.ok) finish(r, g.displayName ?: "") else message = r.error
                                } else message = "Google sign-in did not return an account."
                            } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
                                // closed by the person: nothing to say
                            } catch (e: androidx.credentials.exceptions.NoCredentialException) {
                                message = "No Google account on this phone. Add one in Settings, then try again."
                            } catch (e: Exception) {
                                message = "Google sign-in is not available right now. Use your email instead."
                            }
                            busy = false
                        }
                    }
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("G", color = Color(0xFF4285F4), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text("Continue with Google", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            if (!signingUp) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Forgot password?",
                    color = C.Dim, fontSize = 14.sp,
                    modifier = Modifier.clickable(enabled = !busy) {
                        if (!emailOk) { message = "Type your email above first, then tap Forgot password."; good = false; return@clickable }
                        busy = true
                        scope.launch {
                            val r = Accounts.reset(email.trim())
                            busy = false
                            good = r.ok
                            message = if (r.ok) "We sent a password reset link to ${email.trim()}." else r.error
                        }
                    }
                )
            }

            Spacer(Modifier.height(26.dp))
            Text(
                if (signingUp) "Already have an account? Log in" else "New here? Create an account",
                color = C.Pump, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(enabled = !busy) { signingUp = !signingUp; message = ""; confirm = "" }
            )
        }
    }
}
