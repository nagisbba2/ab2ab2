package af.shizuku.manager.adb
import af.shizuku.manager.R

import android.content.Context
import timber.log.Timber
import android.widget.Toast
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.adb.AdbClient
import af.shizuku.manager.adb.AdbKey
import af.shizuku.manager.adb.PreferenceAdbKeyStore
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.ShizukuStateMachine
import io.sentry.Sentry
import android.app.Activity
import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.utils.SettingsPage

/**
 * ADB accepted the connection but the session never proved shell (uid=2000) or root
 * (uid=0) privileges, so the post-start shell command was skipped instead of being run
 * unprivileged. Surfaced to the starter UI as a warning, not a hard failure.
 */
class AdbShellPrivilegeException :
    IllegalStateException("ADB shell privilege not granted, command not executed")

object AdbStarter {
    private const val TAG = "AdbStarter"

    // Run once the service is up, over the same ADB connection that started it.
    // Change this to any `adb shell` command whose output you want to see in the start log.
    private const val POST_START_SHELL_COMMAND = "echo hello world"

    // True while a connection is holding its socket open waiting for the user to tap
    // "Allow USB debugging?". Read by AdbStartWorker.enqueue() to switch from REPLACE
    // to KEEP so the waiting worker is not cancelled and a second dialog is not queued.
    @Volatile var keyOfferInFlight: Boolean = false

    private fun Context.getActivity(): Activity? {
        var context = this
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }

    /** Returns true for transient connection errors that are not bugs and should not go to Sentry. */
    private fun Throwable.isExpectedAdbError(includeIllegalState: Boolean = false) =
        this is EOFException || this is SocketException || this is SocketTimeoutException ||
        this is ConnectException || this is SSLException ||
        this is AdbKeyException || (includeIllegalState && this is IllegalStateException)

    /**
     * Runs [cmd] through the ADB `shell:` service, streams stdout into [log] and returns
     * the collected output.
     *
     * `shell:` is the very transport that starts the Shizuku process, so a successful
     * run here is the cheapest end-to-end proof that the connection is usable — and
     * the output lands in the same log the user is already looking at.
     */
    private fun AdbClient.runShellCommand(cmd: String, log: ((String) -> Unit)?): String {
        log?.invoke("\n> adb shell $cmd")
        val collected = StringBuilder()
        command("shell:$cmd") { bytes ->
            val text = String(bytes).trimEnd('\r', '\n')
            if (text.isNotEmpty()) {
                collected.append(text)
                log?.invoke(text)
            }
        }
        return collected.toString()
    }

    /**
     * Privilege gate + demo command.
     *
     * ADB authorizes a host key at connection time, but that alone does not prove the
     * session can run shell commands (e.g. adbd already restricted to a limited shell).
     * So explicitly check that `id` reports shell (uid=2000) or root (uid=0); only then
     * notify the UI via [onShellStart] and execute [POST_START_SHELL_COMMAND]. If the
     * check fails, nothing is executed and the failure is reported instead.
     */
    private suspend fun runPostStartShellCheck(
        client: AdbClient,
        log: ((String) -> Unit)?,
        onShellStart: (() -> Unit)?,
    ): Result<String> {
        val idOutput = runCatching { client.runShellCommand("id", log) }.getOrNull().orEmpty()
        val hasAdbPrivilege = Regex("\\buid=(0|2000)\\b").containsMatchIn(idOutput)
        if (!hasAdbPrivilege) {
            log?.invoke(
                "No ADB shell privilege (id reported: ${idOutput.ifEmpty { "no output" }}); command not executed"
            )
            return Result.failure(AdbShellPrivilegeException())
        }
        withContext(Dispatchers.Main) { onShellStart?.invoke() }
        return runCatching { client.runShellCommand(POST_START_SHELL_COMMAND, log) }
            .onFailure { log?.invoke("Shell check failed: ${it.message}") }
    }

    suspend fun startAdb(
        context: Context,
        port: Int,
        log: ((String) -> Unit)? = null,
        // Fired on the main thread right after the session proved it holds ADB shell
        // privileges and immediately before the command runs — never earlier.
        onShellStart: (() -> Unit)? = null,
        // Invoked on the main thread with the outcome of the post-start shell check, so UI
        // layers (e.g. the starter step timeline) can render it inline instead of only in
        // the collapsible log. Failure carries the exception, success carries stdout.
        onShellResult: ((Result<String>) -> Unit)? = null,
    ) {
        if (port !in 1..65535) {
            Timber.tag(TAG).w("startAdb called with invalid port $port — skipping")
            return
        }
        // Only auto-disable wireless debugging after a fully successful start:
        // disabling on failure would tear down the very socket a retry depends on.
        var startedSuccessfully = false
        var shellResult: Result<String>? = null
        suspend fun AdbClient.runCommand(cmd: String) {
            command(cmd) { log?.invoke(String(it)) }
        }

        try {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            log?.invoke("Starting with wireless adb...\n")

            withContext(Dispatchers.IO) {
                val key = runCatching { AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizukux") }
                    .getOrElse {
                        if (it is CancellationException) throw it
                        else throw AdbKeyException(it)
                    }

                var activePort = port
                val tcpMode = ShizukuSettings.getTcpMode()
                val tcpPort = ShizukuSettings.getTcpPort()
                if (tcpMode && activePort != tcpPort) {
                    if (tcpPort !in 1..65535) {
                        Timber.tag(TAG).w("TCP mode enabled but stored TCP port is invalid ($tcpPort) — skipping TCP redirect")
                    } else {
                        log?.invoke("Connecting on port $activePort...")

                        AdbClient("127.0.0.1", activePort, key).use { client ->
                            client.connect()

                            log?.invoke("Successfully connected on port $activePort...")
                            log?.invoke("\nRestarting in TCP mode port: $tcpPort")

                            activePort = tcpPort
                            runCatching {
                                client.command("tcpip:$activePort")
                            }.onFailure { if (it !is EOFException && it !is SocketException) throw it } // Expected when ADB restarts in TCP mode
                        }
                    }
                }

                log?.invoke("Connecting on port $activePort...")

                try {
                    AdbClient("127.0.0.1", activePort, key).use { client ->
                        connectWithRetry(client)
                        log?.invoke("Successfully connected on port $activePort...\n")
                        client.runCommand("shell:${Starter.internalCommand}")
                        // Service is up: gate on real ADB shell privileges, then run the
                        // command. A failure here only surfaces in the UI — it must not
                        // turn a successful start into a failed one.
                        shellResult = runPostStartShellCheck(client, log, onShellStart)
                        ShizukuSettings.setLastPort(activePort)
                        ActivityLogManager.log("Shizuku", context.packageName, "Service started via ADB on port $activePort")
                        ShizukuStateMachine.update()
                        startedSuccessfully = true
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Some ROMs (e.g. HyperOS) ignore the tcpip: redirect while wireless
                    // debugging is active, so adbd never listens on the TCP port. Instead of
                    // failing the whole start, fall back to the original wireless debugging
                    // port and start the service directly (no TCP redirect).
                    if (activePort != port && port in 1..65535) {
                        Timber.tag(TAG).w(e, "TCP redirect to $activePort failed; falling back to port $port")
                        log?.invoke("TCP redirect to $activePort failed (${e.message}); falling back to port $port")
                        activePort = port
                        log?.invoke("Connecting on port $activePort...")
                        try {
                            AdbClient("127.0.0.1", activePort, key).use { client ->
                                connectWithRetry(client)
                                log?.invoke("Successfully connected on port $activePort...\n")
                                client.runCommand("shell:${Starter.internalCommand}")
                                // Same post-start check as the primary path (TCP-mode fallback),
                                // including the ADB privilege gate.
                                shellResult = runPostStartShellCheck(client, log, onShellStart)
                                ShizukuSettings.setLastPort(activePort)
                                ActivityLogManager.log("Shizuku", context.packageName, "Service started via ADB on port $activePort")
                                ShizukuStateMachine.update()
                                startedSuccessfully = true
                            }
                        } catch (e2: Exception) {
                            if (e2 is CancellationException) throw e2
                            // The fallback port failed too: the tcpip: redirect made adbd
                            // re-bind to a brand-new random TLS port (observed on HyperOS),
                            // so the original port is gone and TCP mode can never work here.
                            // Disable TCP mode so the next attempt connects directly without
                            // the tcpip redirect, then let the caller re-detect and retry.
                            Timber.tag(TAG).w(e2, "Fallback to port $port failed; disabling TCP mode")
                            log?.invoke("Fallback failed (${e2.message}); disabling TCP mode")
                            runCatching { ShizukuSettings.setTcpMode(false) }
                            throw e2
                        }
                    } else {
                        throw e
                    }
                }
            }

            // Hand the shell output over on the main thread so UI layers can render it
            // without marshalling threads themselves.
            shellResult?.let { result ->
                withContext(Dispatchers.Main) { onShellResult?.invoke(result) }
            }
        } catch (e: Exception) {
            if (e is SSLException && (e.message?.contains("protocol version") == true || e is javax.net.ssl.SSLProtocolException)) {
                withContext(Dispatchers.Main) {
                    val activity = context.getActivity()
                    if (activity != null && !activity.isFinishing) {
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.adb_error_ssl_title)
                            .setMessage(R.string.adb_error_ssl_message)
                            .setPositiveButton(R.string.adb_error_ssl_button_reset) { _, _ ->
                                SettingsPage.Developer.Options.launch(activity)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } else {
                        // Fallback for non-activity context
                        val themedContext = ContextThemeWrapper(context, R.style.AppTheme)
                        Toast.makeText(themedContext, R.string.adb_error_ssl_message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            if (e !is CancellationException && !e.isExpectedAdbError()) {
                Sentry.captureException(e)
            }
            throw e
        } finally {
            if (startedSuccessfully && ShizukuSettings.getAutoDisableUsbDebugging())
                AdbArm.disarmWifiEnabled(context)
        }
    }

    suspend fun stopTcp(context: Context, port: Int) {
        if (port !in 1..65535) return
        runCatching {
            AdbArm.armUsbDebugging(context)

            if (!EnvironmentUtils.isAdbEnabled()) throw IllegalStateException("ADB is not enabled")

            ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizukux")
            withContext(Dispatchers.IO) {
                AdbClient("127.0.0.1", port, key).use { client ->
                    connectWithRetry(client)
                    client.command("usb:")
                }
            }
        }.onFailure {
            if (it !is CancellationException && !it.isExpectedAdbError(includeIllegalState = true)) {
                Sentry.captureException(it)
            }
            if (EnvironmentUtils.getAdbTcpPort() > 0) {
                ShizukuStateMachine.update()
                withContext(Dispatchers.Main) {
                    val errorMsg = when (it) {
                        is AdbKeyException -> context.getString(R.string.adb_error_key_store)
                        else -> it.message
                    }
                    Toast.makeText(context, context.getString(R.string.adb_error_stop_tcp) + ". ${errorMsg}", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    private suspend fun connectWithRetry(client: AdbClient) {
        var delayTime = 500L
        val maxAttempts = 8
        for (attempt in 1..maxAttempts) {
            try {
                if (attempt > 1) {
                    delay(delayTime)
                    delayTime = (delayTime * 1.5).toLong().coerceAtMost(3000L) // Exponential backoff up to 3s
                }
                client.connect(
                    onKeyOffered = { keyOfferInFlight = true },
                    onKeyOfferDone = { keyOfferInFlight = false },
                )
                break
            } catch (e: Exception) {
                if (
                    attempt == maxAttempts ||
                    e is CancellationException
                ) throw e
            }
        }
    }
}
