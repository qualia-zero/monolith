package com.monolith.core.vfs

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@Suppress("DEPRECATION")
object GoogleDriveAuthManager {
    private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"

    private fun buildSignInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_SCOPE))
            .build()

    fun getSignInIntent(context: Context): Intent =
        GoogleSignIn.getClient(context, buildSignInOptions()).signInIntent

    fun handleSignInResult(data: Intent?): Result<String> {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            val email = account.email ?: account.account?.name
            if (email.isNullOrBlank()) {
                Result.failure(IllegalStateException("Google-Konto enthält keine E-Mail-Adresse."))
            } else {
                Result.success(email)
            }
        } catch (e: ApiException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getSignedInEmail(context: Context): String? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        if (!GoogleSignIn.hasPermissions(account, Scope(DRIVE_SCOPE))) return null
        return account.email ?: account.account?.name
    }

    suspend fun signOut(context: Context) {
        val client = GoogleSignIn.getClient(context, buildSignInOptions())
        suspendCancellableCoroutine { continuation ->
            client.signOut().addOnCompleteListener {
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }
    }

    suspend fun getAccessToken(context: Context): String? =
        withContext(Dispatchers.IO) {
            try {
                val signInAccount = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
                val account = signInAccount.account ?: return@withContext null
                GoogleAuthUtil.getToken(context, account, "oauth2:$DRIVE_SCOPE")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
}
