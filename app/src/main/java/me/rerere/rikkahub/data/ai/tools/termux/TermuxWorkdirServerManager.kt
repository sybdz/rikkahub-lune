package me.rerere.rikkahub.data.ai.tools.termux

import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore

private const val TAG = "TermuxWorkdirServer"

data class TermuxWorkdirServerState(
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val port: Int = 9090,
    val error: String? = null,
)

class TermuxWorkdirServerManager(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val termuxCommandManager: TermuxCommandManager,
) {
    private val _state = MutableStateFlow(TermuxWorkdirServerState())
    val state: StateFlow<TermuxWorkdirServerState> = _state.asStateFlow()

    fun start(
        port: Int,
        workdir: String,
    ) {
        if (_state.value.isLoading) return
        _state.value = _state.value.copy(isLoading = true, port = port, error = null)
        appScope.launch {
            val script = buildStartScript(port = port, workdir = workdir)
            val result = runCatching {
                termuxCommandManager.run(
                    TermuxRunCommandRequest(
                        commandPath = TERMUX_BASH_PATH,
                        arguments = listOf("-lc", script),
                        workdir = TERMUX_HOME_PATH,
                        background = settingsStore.settingsFlow.value.termuxRunInBackground,
                        timeoutMs = 10_000L,
                        label = "RikkaHub workdir http server",
                    )
                )
            }.getOrElse { e ->
                Log.e(TAG, "Start failed", e)
                _state.value = TermuxWorkdirServerState(
                    isRunning = false,
                    isLoading = false,
                    port = port,
                    error = e.message ?: e.javaClass.name,
                )
                return@launch
            }

            if (result.exitCode == 0) {
                _state.value = TermuxWorkdirServerState(
                    isRunning = true,
                    isLoading = false,
                    port = port,
                    error = null,
                )
            } else {
                _state.value = TermuxWorkdirServerState(
                    isRunning = false,
                    isLoading = false,
                    port = port,
                    error = formatError(result),
                )
            }
        }
    }

    fun stop() {
        if (_state.value.isLoading) return
        _state.value = _state.value.copy(isLoading = true, error = null)
        appScope.launch {
            val result = runCatching {
                termuxCommandManager.run(
                    TermuxRunCommandRequest(
                        commandPath = TERMUX_BASH_PATH,
                        arguments = listOf(
                            "-lc",
                            buildStopScript(portHint = settingsStore.settingsFlow.value.termuxWorkdirServerPort),
                        ),
                        workdir = TERMUX_HOME_PATH,
                        background = settingsStore.settingsFlow.value.termuxRunInBackground,
                        timeoutMs = 10_000L,
                        label = "RikkaHub stop workdir http server",
                    )
                )
            }.getOrElse { e ->
                Log.e(TAG, "Stop failed", e)
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: e.javaClass.name)
                return@launch
            }

            if (result.exitCode == 0) {
                _state.value = _state.value.copy(isRunning = false, isLoading = false, error = null)
            } else {
                _state.value = _state.value.copy(isLoading = false, error = formatError(result))
            }
        }
    }

    fun restart(
        port: Int,
        workdir: String,
    ) {
        if (_state.value.isLoading) return
        _state.value = _state.value.copy(isLoading = true, port = port, error = null)
        appScope.launch {
            runCatching {
                termuxCommandManager.run(
                    TermuxRunCommandRequest(
                        commandPath = TERMUX_BASH_PATH,
                        arguments = listOf(
                            "-lc",
                            buildStopScript(portHint = port),
                        ),
                        workdir = TERMUX_HOME_PATH,
                        background = settingsStore.settingsFlow.value.termuxRunInBackground,
                        timeoutMs = 10_000L,
                        label = "RikkaHub stop workdir http server",
                    )
                )
            }.onFailure { e ->
                Log.w(TAG, "Restart stop failed, continue", e)
            }
            val startResult = runCatching {
                termuxCommandManager.run(
                    TermuxRunCommandRequest(
                        commandPath = TERMUX_BASH_PATH,
                        arguments = listOf("-lc", buildStartScript(port = port, workdir = workdir)),
                        workdir = TERMUX_HOME_PATH,
                        background = settingsStore.settingsFlow.value.termuxRunInBackground,
                        timeoutMs = 10_000L,
                        label = "RikkaHub workdir http server",
                    )
                )
            }.getOrElse { e ->
                Log.e(TAG, "Restart start failed", e)
                _state.value = TermuxWorkdirServerState(
                    isRunning = false,
                    isLoading = false,
                    port = port,
                    error = e.message ?: e.javaClass.name,
                )
                return@launch
            }

            if (startResult.exitCode == 0) {
                _state.value = TermuxWorkdirServerState(
                    isRunning = true,
                    isLoading = false,
                    port = port,
                    error = null,
                )
            } else {
                _state.value = TermuxWorkdirServerState(
                    isRunning = false,
                    isLoading = false,
                    port = port,
                    error = formatError(startResult),
                )
            }
        }
    }

    private fun buildStartScript(port: Int, workdir: String): String {
        val safeWorkdir = workdir.replace("'", "'\"'\"'")
        return """
            set -e

            STATE_DIR="$TERMUX_STATE_DIR"
            PID_FILE="${'$'}STATE_DIR/workdir_http_server.pid"
            PORT_FILE="${'$'}STATE_DIR/workdir_http_server.port"
            SERVE_DIR='$safeWorkdir'
            PORT=$port

            mkdir -p "${'$'}STATE_DIR"

            if ! command -v python3 >/dev/null 2>&1; then
              echo "python3 not found. In Termux: pkg install python"
              exit 127
            fi

            if [ -f "${'$'}PID_FILE" ]; then
              OLD_PID="${'$'}(cat \"${'$'}PID_FILE\" 2>/dev/null || true)"
              OLD_PORT="${'$'}(cat \"${'$'}PORT_FILE\" 2>/dev/null || true)"
              if [ -n "${'$'}OLD_PID" ] && kill -0 "${'$'}OLD_PID" 2>/dev/null; then
                if [ "${'$'}OLD_PORT" = "${'$'}PORT" ]; then
                  echo "ALREADY_RUNNING ${'$'}OLD_PID ${'$'}OLD_PORT"
                  exit 0
                fi
                kill "${'$'}OLD_PID" 2>/dev/null || true
                sleep 0.2
                kill -9 "${'$'}OLD_PID" 2>/dev/null || true
              fi
              rm -f "${'$'}PID_FILE" "${'$'}PORT_FILE"
            fi

            cd "${'$'}SERVE_DIR"
            nohup python3 -m http.server "${'$'}PORT" --bind 127.0.0.1 >/dev/null 2>&1 &
            NEW_PID="${'$'}!"
            echo "${'$'}NEW_PID" > "${'$'}PID_FILE"
            echo "${'$'}PORT" > "${'$'}PORT_FILE"

            sleep 0.2
            if kill -0 "${'$'}NEW_PID" 2>/dev/null; then
              echo "STARTED ${'$'}NEW_PID ${'$'}PORT"
              exit 0
            fi

            echo "FAILED_TO_START"
            exit 1
        """.trimIndent()
    }

    private fun buildStopScript(portHint: Int?): String {
        val portHintText = portHint?.toString().orEmpty()
        return """
            set -e

            PORT_HINT="$portHintText"
            FAILED=0
            PORTS=""

            is_port_listening() {
              PORT="${'$'}1"
              case "${'$'}PORT" in
                ''|*[!0-9]*) return 1 ;;
              esac
              HEX="${'$'}(printf '%04X' \"${'$'}PORT\")"
              if awk -v hex=":${'$'}HEX" '${'$'}2 ~ (hex "${'$'}") && ${'$'}4 == "0A" { found=1; exit } END { exit(found?0:1) }' /proc/net/tcp 2>/dev/null; then
                return 0
              fi
              if awk -v hex=":${'$'}HEX" '${'$'}2 ~ (hex "${'$'}") && ${'$'}4 == "0A" { found=1; exit } END { exit(found?0:1) }' /proc/net/tcp6 2>/dev/null; then
                return 0
              fi
              return 1
            }

            kill_port_listeners() {
              PORT="${'$'}1"
              case "${'$'}PORT" in
                ''|*[!0-9]*) return 0 ;;
              esac
              HEX="${'$'}(printf '%04X' \"${'$'}PORT\")"
              for inode in ${'$'}(awk -v hex=":${'$'}HEX" '${'$'}2 ~ (hex "${'$'}") && ${'$'}4 == "0A" {print ${'$'}10}' /proc/net/tcp 2>/dev/null || true); do
                for pid_dir in /proc/[0-9]*; do
                  pid="${'$'}{pid_dir##*/}"
                  for fd in "${'$'}pid_dir"/fd/*; do
                    link="${'$'}(readlink \"${'$'}fd\" 2>/dev/null || true)"
                    if [ "${'$'}link" = "socket:[${'$'}inode]" ]; then
                      kill "${'$'}pid" 2>/dev/null || true
                      sleep 0.1
                      kill -9 "${'$'}pid" 2>/dev/null || true
                      break
                    fi
                  done
                done
              done
              for inode in ${'$'}(awk -v hex=":${'$'}HEX" '${'$'}2 ~ (hex "${'$'}") && ${'$'}4 == "0A" {print ${'$'}10}' /proc/net/tcp6 2>/dev/null || true); do
                for pid_dir in /proc/[0-9]*; do
                  pid="${'$'}{pid_dir##*/}"
                  for fd in "${'$'}pid_dir"/fd/*; do
                    link="${'$'}(readlink \"${'$'}fd\" 2>/dev/null || true)"
                    if [ "${'$'}link" = "socket:[${'$'}inode]" ]; then
                      kill "${'$'}pid" 2>/dev/null || true
                      sleep 0.1
                      kill -9 "${'$'}pid" 2>/dev/null || true
                      break
                    fi
                  done
                done
              done
            }

            stop_in_dir() {
              STATE_DIR="${'$'}1"
              PID_FILE="${'$'}STATE_DIR/workdir_http_server.pid"
              PORT_FILE="${'$'}STATE_DIR/workdir_http_server.port"
              PORT_FROM_FILE="${'$'}(cat \"${'$'}PORT_FILE\" 2>/dev/null || true)"
              case "${'$'}PORT_FROM_FILE" in
                ''|*[!0-9]*) : ;;
                *) PORTS="${'$'}PORTS ${'$'}PORT_FROM_FILE" ;;
              esac

              PID="${'$'}(cat \"${'$'}PID_FILE\" 2>/dev/null || true)"
              if [ -n "${'$'}PID" ] && kill -0 "${'$'}PID" 2>/dev/null; then
                kill "${'$'}PID" 2>/dev/null || true
                sleep 0.2
                if kill -0 "${'$'}PID" 2>/dev/null; then
                  kill -9 "${'$'}PID" 2>/dev/null || true
                  sleep 0.2
                fi
                if kill -0 "${'$'}PID" 2>/dev/null; then
                  echo "FAILED_TO_STOP pid=${'$'}PID"
                  FAILED=1
                fi
              fi

              rm -f "${'$'}PID_FILE" "${'$'}PORT_FILE"
            }

            stop_in_dir "$TERMUX_STATE_DIR"
            if [ -n "${'$'}{HOME:-}" ] && [ "${'$'}HOME" != "$TERMUX_HOME_PATH" ]; then
              stop_in_dir "${'$'}HOME/.rikkahub"
            fi

            case "${'$'}PORT_HINT" in
              ''|*[!0-9]*) : ;;
              *) PORTS="${'$'}PORTS ${'$'}PORT_HINT" ;;
            esac

            for PORT in ${'$'}PORTS; do
              if is_port_listening "${'$'}PORT"; then
                kill_port_listeners "${'$'}PORT"
                sleep 0.2
                if is_port_listening "${'$'}PORT"; then
                  echo "PORT_STILL_LISTENING ${'$'}PORT"
                  FAILED=1
                fi
              fi
            done

            if [ "${'$'}FAILED" -ne 0 ]; then
              exit 1
            fi
            echo "STOPPED"
            exit 0
        """.trimIndent()
    }

    private fun formatError(result: TermuxResult): String {
        return buildString {
            val stderr = result.stderr.trim()
            val stdout = result.stdout.trim()
            val errMsg = result.errMsg?.trim()
            if (stderr.isNotBlank()) append(stderr)
            if (errMsg.isNullOrBlank().not()) {
                if (isNotEmpty()) append('\n')
                append(errMsg)
            }
            if (stdout.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stdout)
            }
            if (isEmpty()) append("Exit code: ${result.exitCode}")
        }
    }

    companion object {
        private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
        private const val TERMUX_HOME_PATH = "/data/data/com.termux/files/home"
        private const val TERMUX_STATE_DIR = "$TERMUX_HOME_PATH/.rikkahub"
    }
}
