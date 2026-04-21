//! Single-instance coordination.
//!
//! When `gg` is invoked from the CLI, it first tries to connect to a running
//! GUI instance over a local socket and ask it to open the requested workspace.
//! If that succeeds, the CLI exits without spawning a new process. Otherwise
//! startup proceeds as normal and the new instance binds the socket itself.
//!
//! The server reuses `try_create_window`, which already focuses an existing
//! window for a given path or creates a new one.

use anyhow::{Context, Result, anyhow};
use interprocess::local_socket::{
    GenericFilePath, Listener, ListenerNonblockingMode, ListenerOptions, Stream, ToFsName,
    traits::{Listener as _, Stream as _},
};
use serde::{Deserialize, Serialize};
use std::{
    env,
    hash::{Hash, Hasher},
    io::{BufRead, BufReader, ErrorKind, Write},
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    thread::{self, JoinHandle},
    time::Duration,
};
use tauri::{AppHandle, Wry};

#[derive(Serialize, Deserialize, Debug)]
pub struct OpenRequest {
    /// Absolute, canonical path to the workspace to open. Clients must resolve
    /// relative paths against their own cwd before sending.
    pub workspace: Option<PathBuf>,
    pub ignore_immutable: bool,
}

pub struct SingleInstanceServer {
    stop_flag: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl Drop for SingleInstanceServer {
    fn drop(&mut self) {
        self.stop_flag.store(true, Ordering::Relaxed);
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

/// Build the per-user socket path. Hashing the home dir keeps the name stable
/// across invocations while isolating users on systems with a shared /tmp.
fn socket_path() -> PathBuf {
    let home = env::var_os("HOME")
        .or_else(|| env::var_os("USERPROFILE"))
        .unwrap_or_default();
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    home.hash(&mut hasher);
    let suffix = format!("{:08x}", hasher.finish() as u32);

    if cfg!(windows) {
        PathBuf::from(format!(r"\\.\pipe\gg-ui-{}", suffix))
    } else {
        env::temp_dir().join(format!("gg-ui-{}.sock", suffix))
    }
}

fn to_name(path: &Path) -> Result<interprocess::local_socket::Name<'static>> {
    path.to_path_buf()
        .to_fs_name::<GenericFilePath>()
        .map_err(|e| anyhow!("invalid socket path {:?}: {}", path, e))
}

/// Try to hand the request off to an already-running GUI instance.
/// Returns `Ok(true)` if the request was delivered, `Ok(false)` if no instance
/// is listening, or `Err` if something unexpected went wrong mid-handshake.
pub fn try_attach(request: &OpenRequest) -> Result<bool> {
    let path = socket_path();
    let name = to_name(&path)?;

    let stream = match Stream::connect(name) {
        Ok(s) => s,
        Err(e) if e.kind() == ErrorKind::NotFound || e.kind() == ErrorKind::ConnectionRefused => {
            return Ok(false);
        }
        Err(e) => return Err(anyhow!("connect to {:?}: {}", path, e)),
    };

    let json = serde_json::to_string(request)?;
    let mut writer = &stream;
    writeln!(writer, "{}", json).context("write request")?;
    writer.flush().context("flush request")?;

    let mut response = String::new();
    BufReader::new(&stream)
        .read_line(&mut response)
        .context("read response")?;

    if response.trim() == "OK" {
        Ok(true)
    } else {
        Err(anyhow!("unexpected response: {:?}", response))
    }
}

/// Bind the listener, clearing a stale socket file if the previous instance
/// crashed without cleaning up.
fn bind(path: &Path) -> Result<Listener> {
    match ListenerOptions::new().name(to_name(path)?).create_sync() {
        Ok(l) => Ok(l),
        Err(e) if e.kind() == ErrorKind::AddrInUse => {
            // probe for a live listener on the existing socket
            if Stream::connect(to_name(path)?).is_ok() {
                return Err(anyhow!("another gg instance is listening on {:?}", path));
            }
            // stale: unlink and retry
            #[cfg(unix)]
            let _ = std::fs::remove_file(path);
            ListenerOptions::new()
                .name(to_name(path)?)
                .create_sync()
                .with_context(|| format!("rebind {:?} after clearing stale socket", path))
        }
        Err(e) => Err(anyhow!("bind {:?}: {}", path, e)),
    }
}

pub fn start_server(app_handle: AppHandle<Wry>) -> Result<SingleInstanceServer> {
    let path = socket_path();
    let listener = bind(&path)?;
    listener
        .set_nonblocking(ListenerNonblockingMode::Both)
        .context("set_nonblocking")?;

    let stop_flag = Arc::new(AtomicBool::new(false));
    let stop_clone = stop_flag.clone();
    let path_for_cleanup = path.clone();

    let handle = thread::spawn(move || {
        while !stop_clone.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok(stream) => {
                    let app = app_handle.clone();
                    thread::spawn(move || {
                        if let Err(err) = handle_request(stream, app) {
                            log::warn!("single-instance request failed: {:#}", err);
                        }
                    });
                }
                Err(ref e) if e.kind() == ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(100));
                }
                Err(e) => {
                    log::warn!("single-instance listener error: {}", e);
                    break;
                }
            }
        }
        #[cfg(unix)]
        let _ = std::fs::remove_file(&path_for_cleanup);
        #[cfg(not(unix))]
        let _ = &path_for_cleanup;
    });

    log::debug!("single-instance server listening at {:?}", path);

    Ok(SingleInstanceServer {
        stop_flag,
        handle: Some(handle),
    })
}

fn handle_request(stream: Stream, app_handle: AppHandle<Wry>) -> Result<()> {
    stream.set_nonblocking(false)?;

    let mut reader = BufReader::new(&stream);
    let mut line = String::new();
    reader.read_line(&mut line).context("read request")?;

    let request: OpenRequest = serde_json::from_str(line.trim()).context("parse request json")?;
    log::debug!("single-instance request: {:?}", request);

    // reply first so the client can return control to the shell immediately
    let mut writer = &stream;
    writeln!(writer, "OK").context("write response")?;
    writer.flush().context("flush response")?;

    // off the listener thread - try_create_window builds a webview and needs
    // to cooperate with tauri's runtime, same as the macOS deep-link handler
    tauri::async_runtime::spawn(async move {
        if let Err(err) = super::try_create_window(
            &app_handle,
            request.workspace,
            Some(request.ignore_immutable),
        ) {
            log::warn!("single-instance try_create_window: {:#}", err);
        }
    });

    Ok(())
}
