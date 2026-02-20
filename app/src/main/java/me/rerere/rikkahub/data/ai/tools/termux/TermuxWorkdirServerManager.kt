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
            DIR_FILE="${'$'}STATE_DIR/workdir_http_server.dir"
            LOG_FILE="${'$'}STATE_DIR/workdir_http_server.log"
            SERVE_DIR='$safeWorkdir'
            PORT=$port

            mkdir -p "${'$'}STATE_DIR"

            if ! command -v python3 >/dev/null 2>&1; then
              echo "python3 not found. In Termux: pkg install python"
              exit 127
            fi

            port_is_free() {
              PORT="${'$'}1"
              python3 - "${'$'}PORT" <<'PY'
import socket
import sys

port = int(sys.argv[1])
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    s.bind(("127.0.0.1", port))
except OSError:
    sys.exit(1)
finally:
    try:
        s.close()
    except Exception:
        pass
sys.exit(0)
PY
            }

            find_server_pids() {
              PORT="${'$'}1"
              DIR="${'$'}2"
              python3 - "${'$'}PORT" "${'$'}DIR" 2>/dev/null <<'PY' || true
import os
import sys

port = sys.argv[1]
serve_dir = sys.argv[2] if len(sys.argv) > 2 else ""

def read_cmdline(pid: str):
    try:
        with open(f"/proc/{pid}/cmdline", "rb") as f:
            raw = f.read()
        if not raw:
            return []
        parts = raw.split(b"\0")
        return [p.decode(errors="ignore") for p in parts if p]
    except Exception:
        return []

def is_workdir_server(args):
    if not args:
        return False
    exe = os.path.basename(args[0])
    if not exe.startswith("python"):
        return False
    try:
        mi = args.index("-m")
    except ValueError:
        return False
    if mi + 1 >= len(args) or args[mi + 1] != "http.server":
        return False
    if port not in args:
        return False
    if "--bind" not in args:
        return False
    bi = args.index("--bind")
    if bi + 1 >= len(args) or args[bi + 1] != "127.0.0.1":
        return False
    if serve_dir:
        if "--directory" not in args:
            return False
        di = args.index("--directory")
        if di + 1 >= len(args) or args[di + 1] != serve_dir:
            return False
    return True

pids = []
for pid in os.listdir("/proc"):
    if not pid.isdigit():
        continue
    args = read_cmdline(pid)
    if is_workdir_server(args):
        pids.append(pid)

sys.stdout.write("\n".join(pids))
PY
            }

            kill_pid() {
              PID="${'$'}1"
              case "${'$'}PID" in
                ''|*[!0-9]*) return 0 ;;
              esac
              kill "${'$'}PID" 2>/dev/null || true
              sleep 0.2
              kill -9 "${'$'}PID" 2>/dev/null || true
            }

            if [ -f "${'$'}PID_FILE" ]; then
              OLD_PID="${'$'}(cat \"${'$'}PID_FILE\" 2>/dev/null || true)"
              OLD_PORT="${'$'}(cat \"${'$'}PORT_FILE\" 2>/dev/null || true)"
              OLD_DIR="${'$'}(cat \"${'$'}DIR_FILE\" 2>/dev/null || true)"
              if [ -n "${'$'}OLD_PID" ] && kill -0 "${'$'}OLD_PID" 2>/dev/null; then
                if [ "${'$'}OLD_PORT" = "${'$'}PORT" ] && [ "${'$'}OLD_DIR" = "${'$'}SERVE_DIR" ]; then
                  echo "ALREADY_RUNNING ${'$'}OLD_PID ${'$'}OLD_PORT"
                  exit 0
                fi
                kill "${'$'}OLD_PID" 2>/dev/null || true
                sleep 0.2
                kill -9 "${'$'}OLD_PID" 2>/dev/null || true
              fi
              rm -f "${'$'}PID_FILE" "${'$'}PORT_FILE" "${'$'}DIR_FILE"
            fi

            if [ ! -d "${'$'}SERVE_DIR" ]; then
              echo "Workdir not found: ${'$'}SERVE_DIR"
              exit 1
            fi

            if ! port_is_free "${'$'}PORT"; then
              CANDIDATES="${'$'}(find_server_pids \"${'$'}PORT\" \"${'$'}SERVE_DIR\" | tr '\n' ' ')"
              if [ -n "${'$'}CANDIDATES" ]; then
                echo "PORT_IN_USE ${'$'}PORT, killing: ${'$'}CANDIDATES"
                for PID in ${'$'}CANDIDATES; do
                  kill_pid "${'$'}PID"
                done
                sleep 0.2
              fi
              for _ in 1 2 3 4 5 6 7 8 9 10; do
                if port_is_free "${'$'}PORT"; then
                  break
                fi
                sleep 0.2
              done
              if ! port_is_free "${'$'}PORT"; then
                echo "PORT_STILL_IN_USE ${'$'}PORT"
                echo "TIP: check Termux processes with: ps -ef | grep http.server"
                exit 98
              fi
            fi

            : > "${'$'}LOG_FILE"
            echo "${'$'}SERVE_DIR" > "${'$'}DIR_FILE"
            nohup python3 -m http.server "${'$'}PORT" --bind 127.0.0.1 --directory "${'$'}SERVE_DIR" > "${'$'}LOG_FILE" 2>&1 &
            NEW_PID="${'$'}!"

            sleep 0.2
            if kill -0 "${'$'}NEW_PID" 2>/dev/null; then
              echo "${'$'}NEW_PID" > "${'$'}PID_FILE"
              echo "${'$'}PORT" > "${'$'}PORT_FILE"
              echo "STARTED ${'$'}NEW_PID ${'$'}PORT"
              exit 0
            fi

            echo "FAILED_TO_START"
            tail -n 50 "${'$'}LOG_FILE" 2>/dev/null || true
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
            CLEAN_FILES=""

            find_server_pids() {
              PORT="${'$'}1"
              DIR="${'$'}2"
              if ! command -v python3 >/dev/null 2>&1; then
                return 0
              fi
              python3 - "${'$'}PORT" "${'$'}DIR" 2>/dev/null <<'PY' || true
import os
import sys

port = sys.argv[1]
serve_dir = sys.argv[2] if len(sys.argv) > 2 else ""

def read_cmdline(pid: str):
    try:
        with open(f"/proc/{pid}/cmdline", "rb") as f:
            raw = f.read()
        if not raw:
            return []
        parts = raw.split(b"\0")
        return [p.decode(errors="ignore") for p in parts if p]
    except Exception:
        return []

def is_workdir_server(args):
    if not args:
        return False
    exe = os.path.basename(args[0])
    if not exe.startswith("python"):
        return False
    try:
        mi = args.index("-m")
    except ValueError:
        return False
    if mi + 1 >= len(args) or args[mi + 1] != "http.server":
        return False
    if port not in args:
        return False
    if "--bind" not in args:
        return False
    bi = args.index("--bind")
    if bi + 1 >= len(args) or args[bi + 1] != "127.0.0.1":
        return False
    if serve_dir:
        if "--directory" not in args:
            return False
        di = args.index("--directory")
        if di + 1 >= len(args) or args[di + 1] != serve_dir:
            return False
    return True

pids = []
for pid in os.listdir("/proc"):
    if not pid.isdigit():
        continue
    args = read_cmdline(pid)
    if is_workdir_server(args):
        pids.append(pid)

sys.stdout.write("\n".join(pids))
PY
            }

            kill_pid() {
              PID="${'$'}1"
              case "${'$'}PID" in
                ''|*[!0-9]*) return 0 ;;
              esac
              kill "${'$'}PID" 2>/dev/null || true
              sleep 0.2
              kill -9 "${'$'}PID" 2>/dev/null || true
            }

            port_is_free() {
              PORT="${'$'}1"
              if ! command -v python3 >/dev/null 2>&1; then
                return 0
              fi
              python3 - "${'$'}PORT" <<'PY'
import socket
import sys

port = int(sys.argv[1])
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    s.bind(("127.0.0.1", port))
except OSError:
    sys.exit(1)
finally:
    try:
        s.close()
    except Exception:
        pass
sys.exit(0)
PY
            }

            wait_port_free() {
              PORT="${'$'}1"
              for _ in 1 2 3 4 5 6 7 8 9 10; do
                if port_is_free "${'$'}PORT"; then
                  return 0
                fi
                sleep 0.2
              done
              return 1
            }

            stop_in_dir() {
              STATE_DIR="${'$'}1"
              PID_FILE="${'$'}STATE_DIR/workdir_http_server.pid"
              PORT_FILE="${'$'}STATE_DIR/workdir_http_server.port"
              DIR_FILE="${'$'}STATE_DIR/workdir_http_server.dir"
              CLEAN_FILES="${'$'}CLEAN_FILES ${'$'}PID_FILE ${'$'}PORT_FILE ${'$'}DIR_FILE"
              PORT_FROM_FILE="${'$'}(cat \"${'$'}PORT_FILE\" 2>/dev/null || true)"
              DIR_FROM_FILE="${'$'}(cat \"${'$'}DIR_FILE\" 2>/dev/null || true)"
              case "${'$'}PORT_FROM_FILE" in
                ''|*[!0-9]*) : ;;
                *) PORTS="${'$'}PORTS ${'$'}PORT_FROM_FILE" ;;
              esac

              PID="${'$'}(cat \"${'$'}PID_FILE\" 2>/dev/null || true)"
              if [ -n "${'$'}PID" ] && kill -0 "${'$'}PID" 2>/dev/null; then
                kill_pid "${'$'}PID"
                if kill -0 "${'$'}PID" 2>/dev/null; then
                  echo "FAILED_TO_STOP pid=${'$'}PID"
                  FAILED=1
                fi
              fi

              case "${'$'}PORT_FROM_FILE" in
                ''|*[!0-9]*) : ;;
                *)
                  for PID in ${'$'}(find_server_pids "${'$'}PORT_FROM_FILE" "${'$'}DIR_FROM_FILE"); do
                    kill_pid "${'$'}PID"
                  done
                  if ! wait_port_free "${'$'}PORT_FROM_FILE"; then
                    echo "PORT_STILL_LISTENING ${'$'}PORT_FROM_FILE"
                    FAILED=1
                  fi
                ;;
              esac
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
              for PID in ${'$'}(find_server_pids "${'$'}PORT" ""); do
                kill_pid "${'$'}PID"
              done
              if ! wait_port_free "${'$'}PORT"; then
                echo "PORT_STILL_LISTENING ${'$'}PORT"
                FAILED=1
              fi
            done

            if [ "${'$'}FAILED" -ne 0 ]; then
              exit 1
            fi
            rm -f ${'$'}CLEAN_FILES
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
