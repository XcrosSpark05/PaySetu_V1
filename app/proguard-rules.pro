# PaySetu Project ProGuard Rules
# Location: app/proguard-rules.pro

# ---------------------------------------------------------------------------------
# 1. JETPACK COMPOSE FIX (Critical for Lag/Lock Verification)
# ---------------------------------------------------------------------------------
# Prevents R8 from breaking Compose Snapshot State lock verification.
# This fixes the "failed lock verification and will run slower" issue and 800ms lags.
-keepclassmembers class androidx.compose.runtime.snapshots.** { *; }

# ---------------------------------------------------------------------------------
# 2. ROOM DATABASE & LEDGER INTEGRITY
# ---------------------------------------------------------------------------------
# Prevent R8 from shrinking or obfuscating your Ledger entities and Room logic.
# If these are renamed, the SQL database won't find the correct columns/tables.
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.Dao
-keep class com.paysetu.app.data.ledger.entity.** { *; }

# ---------------------------------------------------------------------------------
# 3. SECURITY & CRYPTOGRAPHY (Keystore)
# ---------------------------------------------------------------------------------
# Your TransactionSigner and Keystore implementation must not be renamed,
# otherwise the link to the phone's Secure Element/TEE may break.
-keep class com.paysetu.app.security.** { *; }
-keep class com.paysetu.app.domain.security.** { *; }

# Preserve JCA (Java Cryptography Architecture) providers
-keepclassmembers class * extends java.security.Provider { *; }
-keep class org.bouncycastle.** { *; }

# ---------------------------------------------------------------------------------
# 4. KOTLIN COROUTINES (Background Workers)
# ---------------------------------------------------------------------------------
# Ensures background initialization and ledger updates remain stable.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ---------------------------------------------------------------------------------
# 5. GENERAL SYSTEM RULES
# ---------------------------------------------------------------------------------
# Preserve line numbers for readable crash reports in Logcat
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile