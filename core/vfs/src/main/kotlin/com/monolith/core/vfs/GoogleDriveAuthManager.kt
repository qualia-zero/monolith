package com.monolith.core.vfs

import android.accounts.Account
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
    private const val PREFS_NAME = "monolith_drive_auth"
    private const val KEY_ACCOUNTS = "drive_accounts"
    private const val KEY_ACCOUNTS_ORDER = "drive_accounts_order"
    private const val KEY_ACTIVE_EMAIL = "active_drive_email"

    private fun buildSignInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_SCOPE))
            .build()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSignInIntent(context: Context): Intent =
        GoogleSignIn.getClient(context, buildSignInOptions()).signInIntent

    suspend fun getAddAccountIntent(context: Context): Intent {
        val client = GoogleSignIn.getClient(context, buildSignInOptions())
        // Clear GMS cached last selection so the account picker opens for additional accounts.
        if (GoogleSignIn.getLastSignedInAccount(context) != null) {
            suspendCancellableCoroutine { continuation ->
                client.signOut().addOnCompleteListener {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }
        }
        return client.signInIntent
    }

    fun handleSignInResult(context: Context, data: Intent?): Result<String> {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            val email = account.email ?: account.account?.name
            if (email.isNullOrBlank()) {
                Result.failure(IllegalStateException("Google-Konto enthält keine E-Mail-Adresse."))
            } else {
                addAccount(context, email)
                Result.success(email)
            }
        } catch (e: ApiException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getSignedInAccounts(context: Context): List<String> {
        val sp = prefs(context)
        val storedSet = sp.getStringSet(KEY_ACCOUNTS, null)?.filter { it.isNotBlank() }.orEmpty()
        if (storedSet.isNotEmpty()) {
            val ordered = sp.getString(KEY_ACCOUNTS_ORDER, null)
                ?.split('\n')
                ?.filter { it.isNotBlank() && it in storedSet }
                .orEmpty()
            val remaining = (storedSet - ordered.toSet()).sorted()
            return ordered + remaining
        }

        val last = GoogleSignIn.getLastSignedInAccount(context)
        if (last != null && GoogleSignIn.hasPermissions(last, Scope(DRIVE_SCOPE))) {
            val email = last.email ?: last.account?.name
            if (!email.isNullOrBlank()) {
                addAccount(context, email)
                return listOf(email)
            }
        }
        return emptyList()
    }

    fun saveAccountOrder(context: Context, orderedAccounts: List<String>) {
        val clean = orderedAccounts.filter { it.isNotBlank() }.distinct()
        prefs(context).edit()
            .putStringSet(KEY_ACCOUNTS, clean.toSet())
            .putString(KEY_ACCOUNTS_ORDER, clean.joinToString("\n"))
            .apply()
    }

    fun getActiveEmail(context: Context): String? {
        val accounts = getSignedInAccounts(context)
        if (accounts.isEmpty()) return null
        val saved = prefs(context).getString(KEY_ACTIVE_EMAIL, null)
        return if (saved != null && saved in accounts) saved else accounts.first()
    }

    fun setActiveEmail(context: Context, email: String) {
        prefs(context).edit().putString(KEY_ACTIVE_EMAIL, email).apply()
    }

    private fun addAccount(context: Context, email: String) {
        val current = getSignedInAccountsWithoutLastFallback(context)
        val updated = if (email in current) current else current + email
        prefs(context).edit()
            .putStringSet(KEY_ACCOUNTS, updated.toSet())
            .putString(KEY_ACCOUNTS_ORDER, updated.joinToString("\n"))
            .putString(KEY_ACTIVE_EMAIL, email)
            .apply()
    }

    private fun getSignedInAccountsWithoutLastFallback(context: Context): List<String> {
        val sp = prefs(context)
        val storedSet = sp.getStringSet(KEY_ACCOUNTS, null)?.filter { it.isNotBlank() }.orEmpty()
        if (storedSet.isEmpty()) return emptyList()
        val ordered = sp.getString(KEY_ACCOUNTS_ORDER, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() && it in storedSet }
            .orEmpty()
        val remaining = (storedSet - ordered.toSet()).sorted()
        return ordered + remaining
    }

    fun getSignedInEmail(context: Context): String? = getActiveEmail(context)

    suspend fun signOut(context: Context, email: String? = getActiveEmail(context)) {
        val current = getSignedInAccountsWithoutLastFallback(context)
        val updated = if (email != null) current.filterNot { it == email } else emptyList()
        val nextActive = updated.firstOrNull()
        prefs(context).edit()
            .putStringSet(KEY_ACCOUNTS, updated.toSet())
            .putString(KEY_ACCOUNTS_ORDER, updated.joinToString("\n"))
            .putString(KEY_ACTIVE_EMAIL, nextActive)
            .apply()

        val client = GoogleSignIn.getClient(context, buildSignInOptions())
        suspendCancellableCoroutine { continuation ->
            client.signOut().addOnCompleteListener {
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }
    }

    suspend fun getAccessToken(
        context: Context,
        email: String? = getActiveEmail(context),
    ): String? =
        withContext(Dispatchers.IO) {
            try {
                val targetEmail = email?.takeIf { it.isNotBlank() } ?: run {
                    val last = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
                    last.email ?: last.account?.name ?: return@withContext null
                }
                GoogleAuthUtil.getToken(context, Account(targetEmail, "com.google"), "oauth2:$DRIVE_SCOPE")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
}
