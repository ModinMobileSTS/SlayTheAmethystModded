#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]

use std::{
    env,
    fs::{self, File, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::mpsc::{self, Receiver},
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

#[cfg(target_os = "windows")]
use std::ptr::null_mut;

use arboard::Clipboard;
use reqwest::{blocking::Client, header, StatusCode};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tray_icon::{
    menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem, Submenu},
    Icon, TrayIconBuilder,
};
use url::Url;
use winit::{
    event::Event,
    event_loop::{ControlFlow, EventLoop, EventLoopProxy},
};

#[cfg(target_os = "windows")]
use windows_sys::Win32::{
    Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS, HWND, LPARAM, LRESULT, WPARAM},
    System::LibraryLoader::GetModuleHandleW,
    System::Threading::CreateMutexW,
    UI::WindowsAndMessaging::{
        CreateWindowExW, DefWindowProcW, DestroyWindow, DispatchMessageW, GetMessageW,
        GetWindowLongPtrW, GetWindowTextLengthW, GetWindowTextW, PostQuitMessage, RegisterClassW,
        SetWindowLongPtrW, ShowWindow, TranslateMessage, BS_DEFPUSHBUTTON, CREATESTRUCTW,
        CS_HREDRAW, CS_VREDRAW, CW_USEDEFAULT, ES_AUTOHSCROLL, ES_PASSWORD, GWLP_USERDATA, HMENU,
        MSG, SW_SHOW, WM_CLOSE, WM_COMMAND, WM_DESTROY, WM_NCCREATE, WNDCLASSW, WS_BORDER,
        WS_CAPTION, WS_CHILD, WS_EX_DLGMODALFRAME, WS_OVERLAPPED, WS_SYSMENU, WS_VISIBLE,
    },
};

const APP_NAME: &str = "Slay the Amethyst Online";
const APP_VERSION: &str = "desktop-0.1.0";
// The Room API parses this field as a semantic app version (x.y.z[-suffix]).
const CLIENT_VERSION: &str = "1.5.7-dev1";
const TOGETHER_IN_SPIRE_PORT: u16 = 33455;
const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(15);
const CLOUD_CONTROL_URL: &str = "https://github.com/ModinMobileSTS/SlayTheAmethystResource/releases/download/Resource/cloud-control.json";
const EMBEDDED_EASYTIER_CORE: &[u8] = include_bytes!("../assets/easytier-core.exe");
const EMBEDDED_APP_ICON: &[u8] = include_bytes!("../assets/ic_launcher_amethyst.png");
const EMBEDDED_CLOUD_CONTROL: &[u8] = include_bytes!("../assets/cloud-control.json");
const EMBEDDED_EASYTIER_CORE_SHA256: &str =
    "da7eb2d24b5416f3d3407636949e964a0750e3f9dc53a828cb6799a57ead445d";
const EMBEDDED_EASYTIER_RUNTIME: &[(&str, &[u8])] = &[
    ("packet.dll", include_bytes!("../assets/packet.dll")),
    ("wintun.dll", include_bytes!("../assets/wintun.dll")),
    (
        "WinDivert64.sys",
        include_bytes!("../assets/WinDivert64.sys"),
    ),
];

struct SingleInstance {
    #[cfg(target_os = "windows")]
    handle: windows_sys::Win32::Foundation::HANDLE,
}

impl SingleInstance {
    fn acquire() -> Result<Option<Self>, String> {
        #[cfg(target_os = "windows")]
        {
            let name = "Global\\SlayTheAmethystOnlineTray"
                .encode_utf16()
                .chain(std::iter::once(0))
                .collect::<Vec<_>>();
            let handle = unsafe { CreateMutexW(null_mut(), 1, name.as_ptr()) };
            if handle.is_null() {
                return Err("无法创建应用互斥体。".to_owned());
            }
            if unsafe { GetLastError() } == ERROR_ALREADY_EXISTS {
                unsafe {
                    CloseHandle(handle);
                }
                return Ok(None);
            }
            return Ok(Some(Self { handle }));
        }

        #[cfg(not(target_os = "windows"))]
        {
            Ok(Some(Self {}))
        }
    }
}

#[cfg(target_os = "windows")]
impl Drop for SingleInstance {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.handle);
        }
    }
}

#[derive(Clone)]
struct AppPaths {
    root: PathBuf,
}

impl AppPaths {
    fn discover() -> Self {
        let root = env::var_os("APPDATA")
            .filter(|value| !value.is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                env::var_os("HOME")
                    .map(PathBuf::from)
                    .unwrap_or_else(|| PathBuf::from("."))
            })
            .join("SlayTheAmethystOnline");
        Self { root }
    }

    fn runtime_dir(&self) -> PathBuf {
        self.root.join("runtime")
    }

    fn state_file(&self) -> PathBuf {
        self.runtime_dir().join("connection-state.json")
    }

    fn config_file(&self) -> PathBuf {
        self.runtime_dir().join("easytier.toml")
    }

    fn log_file(&self) -> PathBuf {
        self.runtime_dir().join("easytier.log")
    }

    fn easytier_executable(&self) -> PathBuf {
        self.runtime_dir().join("easytier-core.exe")
    }
}

fn default_player_name() -> String {
    env::var("USERNAME")
        .or_else(|_| env::var("USER"))
        .unwrap_or_else(|_| "Player".to_owned())
}

#[derive(Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CloudControl {
    #[serde(default)]
    enabled: bool,
    #[serde(default)]
    room_api_base_url: String,
}

#[derive(Clone)]
struct AppConfig {
    cloud_control: CloudControl,
    player_name: String,
    player_id: String,
    easytier_executable: PathBuf,
}

#[derive(Deserialize)]
struct CloudControlResponse {
    #[serde(rename = "easyTier", default)]
    easy_tier: CloudControl,
}

fn load_config(paths: &AppPaths) -> Result<AppConfig, String> {
    let client = Client::builder()
        .timeout(Duration::from_secs(20))
        .user_agent(format!("SlayTheAmethystDesktop/{APP_VERSION}"))
        .build()
        .map_err(|error| format!("Could not create cloud control client: {error}"))?;
    let response = client.get(CLOUD_CONTROL_URL).send().ok();
    let text = match response {
        Some(response) if response.status().is_success() => response
            .text()
            .map_err(|error| format!("Could not read cloud control response: {error}"))?,
        Some(response) => {
            write_startup_log(
                paths,
                &format!(
                    "Cloud control returned HTTP {}; using bundled defaults.",
                    response.status()
                ),
            );
            String::from_utf8_lossy(EMBEDDED_CLOUD_CONTROL).into_owned()
        }
        None => {
            write_startup_log(
                paths,
                "Could not load cloud control; using bundled defaults.",
            );
            String::from_utf8_lossy(EMBEDDED_CLOUD_CONTROL).into_owned()
        }
    };
    let cloud_control = serde_json::from_str::<CloudControlResponse>(&text)
        .map_err(|error| format!("Cloud control returned invalid JSON: {error}"))?
        .easy_tier;
    if !cloud_control.enabled {
        return Err("EasyTier room support is disabled by cloud control.".to_owned());
    }
    if cloud_control.room_api_base_url.trim().is_empty() {
        return Err("Cloud control did not provide an EasyTier Room API URL.".to_owned());
    }
    let executable = ensure_embedded_easytier(paths)?;
    Ok(AppConfig {
        cloud_control,
        player_name: default_player_name(),
        player_id: stable_player_id(),
        easytier_executable: executable,
    })
}

fn write_startup_log(paths: &AppPaths, message: &str) {
    let _ = fs::create_dir_all(paths.runtime_dir());
    let _ = fs::write(
        paths.runtime_dir().join("startup.log"),
        format!("{}\n{}\n", now_ms(), message),
    );
}

fn ensure_embedded_easytier(paths: &AppPaths) -> Result<PathBuf, String> {
    let executable = paths.easytier_executable();
    let parent = executable
        .parent()
        .ok_or_else(|| "Embedded EasyTier executable has no parent directory.".to_owned())?;
    fs::create_dir_all(parent).map_err(io_error)?;
    write_embedded_asset(
        &executable,
        EMBEDDED_EASYTIER_CORE,
        Some(EMBEDDED_EASYTIER_CORE_SHA256),
    )?;
    for (name, bytes) in EMBEDDED_EASYTIER_RUNTIME {
        write_embedded_asset(&parent.join(name), bytes, None)?;
    }
    Ok(executable)
}

fn write_embedded_asset(
    path: &Path,
    bytes: &[u8],
    expected_sha256: Option<&str>,
) -> Result<(), String> {
    if path.is_file() {
        let existing = fs::read(path).map_err(io_error)?;
        let matches = expected_sha256
            .map(|expected| hex_sha256_bytes(&existing) == expected)
            .unwrap_or_else(|| existing == bytes);
        if matches {
            return Ok(());
        }
    }
    let parent = path
        .parent()
        .ok_or_else(|| "Embedded runtime asset has no parent directory.".to_owned())?;
    let temporary = parent.join(format!(
        ".{}.{}.tmp",
        path.file_name().unwrap().to_string_lossy(),
        now_ms()
    ));
    fs::write(&temporary, bytes).map_err(io_error)?;
    if path.is_file() {
        fs::remove_file(path).map_err(io_error)?;
    }
    fs::rename(&temporary, path).map_err(io_error)
}

fn stable_player_id() -> String {
    let computer = env::var("COMPUTERNAME")
        .or_else(|_| env::var("HOSTNAME"))
        .unwrap_or_default();
    let user = env::var("USERNAME")
        .or_else(|_| env::var("USER"))
        .unwrap_or_default();
    format!(
        "desktop-{}",
        &hex_sha256(&format!("{computer}\n{user}"))[..24]
    )
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ConnectionState {
    enabled: bool,
    can_connect: bool,
    status: String,
    mode: String,
    failure_category: String,
    session_id: String,
    room_id: String,
    entry_node_url: String,
    config_server_url: String,
    acl_group: String,
    expires_at_epoch_seconds: Option<u64>,
    started_at_ms: Option<u64>,
    connected_at_ms: Option<u64>,
    last_updated_at_ms: u64,
    last_error_summary: String,
    diagnostics_summary_path: String,
    assigned_ipv4_cidr: String,
    current_player_id: String,
    room_owner_player_id: String,
    room_owner_ipv4_cidr: String,
    peer_count: Option<u32>,
    relay_server_description: String,
    last_session_state: String,
    last_room_state: String,
    user_initiated: bool,
}

impl ConnectionState {
    fn disconnected(config: &AppConfig) -> Self {
        Self {
            enabled: true,
            can_connect: config.cloud_control.enabled
                && !config.cloud_control.room_api_base_url.trim().is_empty(),
            status: "DISCONNECTED".to_owned(),
            mode: "Room".to_owned(),
            failure_category: "None".to_owned(),
            session_id: String::new(),
            room_id: String::new(),
            entry_node_url: String::new(),
            config_server_url: String::new(),
            acl_group: String::new(),
            expires_at_epoch_seconds: None,
            started_at_ms: None,
            connected_at_ms: None,
            last_updated_at_ms: now_ms(),
            last_error_summary: String::new(),
            diagnostics_summary_path: String::new(),
            assigned_ipv4_cidr: String::new(),
            current_player_id: config.player_id.clone(),
            room_owner_player_id: String::new(),
            room_owner_ipv4_cidr: String::new(),
            peer_count: None,
            relay_server_description: String::new(),
            last_session_state: String::new(),
            last_room_state: String::new(),
            user_initiated: true,
        }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RoomListResponse {
    #[serde(default)]
    rooms: Vec<RoomListItem>,
    next_offset: Option<u32>,
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RoomListItem {
    room_id: String,
    owner_display_name: String,
    #[serde(default)]
    description: String,
    #[serde(default)]
    has_password: bool,
    #[serde(default)]
    online_member_count: u32,
    #[serde(default)]
    member_count: u32,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RoomInfo {
    owner_player_id: String,
    #[serde(default)]
    members: Vec<RoomMember>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RoomMember {
    player_id: String,
    #[serde(default)]
    assigned_ipv4_cidr: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Session {
    session_id: String,
    room_id: String,
    entry_node_url: String,
    #[serde(default)]
    config_server_url: String,
    #[serde(default)]
    acl_group: String,
    network_secret: String,
    #[serde(default)]
    assigned_ipv4_cidr: String,
    session_token: String,
    #[serde(default)]
    expires_at: Option<u64>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SessionRuntime {
    #[serde(default)]
    session_state: String,
    #[serde(default)]
    room_state: String,
    peer_count: Option<u32>,
    #[serde(default)]
    assigned_ipv4_cidr: String,
    #[serde(default)]
    relay_server_description: String,
}

#[derive(Deserialize)]
struct ApiErrorResponse {
    #[serde(default)]
    message: String,
}

struct RoomApi {
    client: Client,
    base_url: Url,
}

impl RoomApi {
    fn new(base_url: &str) -> Result<Self, String> {
        let normalized = format!("{}/", base_url.trim().trim_end_matches('/'));
        let base_url = Url::parse(&normalized)
            .map_err(|error| format!("Invalid online service URL: {error}"))?;
        let client = Client::builder()
            .timeout(Duration::from_secs(20))
            .user_agent(format!("SlayTheAmethystDesktop/{APP_VERSION}"))
            .build()
            .map_err(|error| format!("Could not create Room API client: {error}"))?;
        Ok(Self { client, base_url })
    }

    fn list_rooms(&self) -> Result<Vec<RoomListItem>, String> {
        let mut rooms = Vec::new();
        let mut offset = 0_u32;
        for _ in 0..200 {
            let offset_text = offset.to_string();
            let response: RoomListResponse = self.request_json(
                self.client
                    .get(self.url(&["api", "lan", "rooms"]))
                    .query(&[("limit", "50"), ("offset", offset_text.as_str())]),
            )?;
            rooms.extend(response.rooms);
            let Some(next) = response.next_offset else {
                break;
            };
            if next <= offset {
                break;
            }
            offset = next;
        }
        Ok(rooms)
    }

    fn start_session(
        &self,
        config: &AppConfig,
        room_id: &str,
        password: &str,
        description: &str,
        create_only: bool,
    ) -> Result<Session, String> {
        #[derive(Serialize)]
        #[serde(rename_all = "camelCase")]
        struct StartRequest<'a> {
            room_id: &'a str,
            player_id: &'a str,
            display_name: String,
            client_version: &'a str,
            device_summary: String,
            mac_address: String,
            password: &'a str,
            description: &'a str,
            create_only: bool,
        }

        let request = StartRequest {
            room_id,
            player_id: &config.player_id,
            display_name: if config.player_name.trim().is_empty() {
                "Player".to_owned()
            } else {
                config.player_name.trim().to_owned()
            },
            client_version: CLIENT_VERSION,
            device_summary: format!("Desktop {}", env::consts::OS),
            mac_address: stable_mac_address(&config.player_id),
            password,
            description,
            create_only,
        };
        self.request_json(
            self.client
                .post(self.url(&["api", "lan", "session", "start"]))
                .json(&request),
        )
    }

    fn report_runtime(
        &self,
        session: &Session,
        relay_server_description: &str,
    ) -> Result<SessionRuntime, String> {
        #[derive(Serialize)]
        #[serde(rename_all = "camelCase")]
        struct RuntimeRequest<'a> {
            session_id: &'a str,
            assigned_ipv4_cidr: &'a str,
            relay_server_description: &'a str,
        }

        self.request_json(
            self.authorized(
                self.client
                    .post(self.url(&["api", "lan", "session", "runtime"]))
                    .json(&RuntimeRequest {
                        session_id: &session.session_id,
                        assigned_ipv4_cidr: &session.assigned_ipv4_cidr,
                        relay_server_description,
                    }),
                &session.session_token,
            ),
        )
    }

    fn stop_session(&self, session: &Session) -> Result<(), String> {
        #[derive(Serialize)]
        #[serde(rename_all = "camelCase")]
        struct StopRequest<'a> {
            session_id: &'a str,
        }

        self.request_empty(
            self.authorized(
                self.client
                    .post(self.url(&["api", "lan", "session", "stop"]))
                    .json(&StopRequest {
                        session_id: &session.session_id,
                    }),
                &session.session_token,
            ),
        )
    }

    fn room_info(&self, room_id: &str) -> Result<RoomInfo, String> {
        self.request_json(self.client.get(self.url(&["api", "lan", "rooms", room_id])))
    }

    fn authorized(
        &self,
        request: reqwest::blocking::RequestBuilder,
        token: &str,
    ) -> reqwest::blocking::RequestBuilder {
        request.header(header::AUTHORIZATION, format!("Bearer {token}"))
    }

    fn url(&self, parts: &[&str]) -> Url {
        let mut url = self.base_url.clone();
        {
            let mut path = url.path_segments_mut().expect("HTTP URL cannot be a base");
            path.pop_if_empty();
            path.extend(parts);
        }
        url
    }

    fn request_json<T: DeserializeOwned>(
        &self,
        request: reqwest::blocking::RequestBuilder,
    ) -> Result<T, String> {
        let response = request
            .send()
            .map_err(|error| format!("Room API request failed: {error}"))?;
        let status = response.status();
        let text = response.text().unwrap_or_default();
        if !status.is_success() {
            return Err(api_error(status, &text));
        }
        serde_json::from_str(&text)
            .map_err(|error| format!("Room API returned invalid JSON: {error}"))
    }

    fn request_empty(&self, request: reqwest::blocking::RequestBuilder) -> Result<(), String> {
        let response = request
            .send()
            .map_err(|error| format!("Room API request failed: {error}"))?;
        if response.status().is_success() {
            Ok(())
        } else {
            let status = response.status();
            let text = response.text().unwrap_or_default();
            Err(api_error(status, &text))
        }
    }
}

fn api_error(status: StatusCode, body: &str) -> String {
    let message = serde_json::from_str::<ApiErrorResponse>(body)
        .ok()
        .map(|error| error.message)
        .filter(|message| !message.trim().is_empty())
        .unwrap_or_else(|| body.split_whitespace().collect::<Vec<_>>().join(" "));
    format!(
        "EasyTier Room API failed: HTTP {status}{}",
        if message.is_empty() {
            String::new()
        } else {
            format!(" - {message}")
        }
    )
}

enum WorkerCommand {
    Refresh,
    Connect {
        room_id: String,
        password: String,
        description: String,
        create_only: bool,
    },
    Disconnect,
    Shutdown,
}

enum AppEvent {
    Worker(WorkerEvent),
}

enum WorkerEvent {
    Rooms(Vec<RoomListItem>),
    Connecting(String),
    Connected { room_id: String, address: String },
    Disconnected,
    Error(String),
    ShutdownComplete,
}

struct ActiveConnection {
    session: Session,
    process: Child,
    state: ConnectionState,
    next_heartbeat: std::time::Instant,
}

struct Worker {
    paths: AppPaths,
    config: AppConfig,
    active: Option<ActiveConnection>,
    proxy: EventLoopProxy<AppEvent>,
}

impl Worker {
    fn run(mut self, receiver: Receiver<WorkerCommand>) {
        self.remove_runtime_config();
        loop {
            let timeout = self
                .active
                .as_ref()
                .map(|active| {
                    active
                        .next_heartbeat
                        .saturating_duration_since(std::time::Instant::now())
                })
                .unwrap_or(Duration::from_secs(60));
            match receiver.recv_timeout(timeout) {
                Ok(WorkerCommand::Refresh) => self.refresh(),
                Ok(WorkerCommand::Connect {
                    room_id,
                    password,
                    description,
                    create_only,
                }) => self.connect(&room_id, &password, &description, create_only),
                Ok(WorkerCommand::Disconnect) => self.disconnect(),
                Ok(WorkerCommand::Shutdown) => {
                    self.disconnect();
                    self.emit(WorkerEvent::ShutdownComplete);
                    break;
                }
                Err(mpsc::RecvTimeoutError::Timeout) => self.heartbeat(),
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    self.disconnect();
                    break;
                }
            }
        }
    }

    fn refresh(&self) {
        match self.api().and_then(|api| api.list_rooms()) {
            Ok(rooms) => self.emit(WorkerEvent::Rooms(rooms)),
            Err(error) => self.emit(WorkerEvent::Error(error)),
        }
    }

    fn connect(&mut self, room_id: &str, password: &str, description: &str, create_only: bool) {
        self.disconnect();
        self.emit(WorkerEvent::Connecting(room_id.to_owned()));
        let result = (|| {
            if room_id.trim().is_empty() {
                return Err("Room ID is empty.".to_owned());
            }
            let executable = &self.config.easytier_executable;
            if !executable.is_file() {
                return Err(format!(
                    "EasyTier executable was not found: {}",
                    executable.display()
                ));
            }
            let api = self.api()?;
            let session = api.start_session(
                &self.config,
                room_id.trim(),
                password,
                description,
                create_only,
            )?;
            let room_info = match api.room_info(&session.room_id) {
                Ok(room_info) => room_info,
                Err(error) => {
                    let _ = api.stop_session(&session);
                    return Err(error);
                }
            };
            if let Err(error) = self.write_connecting_state(&session, &room_info) {
                let _ = api.stop_session(&session);
                return Err(error);
            }
            let process = match self.start_easytier(executable, &session) {
                Ok(process) => process,
                Err(error) => {
                    let _ = api.stop_session(&session);
                    self.remove_runtime_config();
                    return Err(error);
                }
            };
            let runtime = match api.report_runtime(&session, "") {
                Ok(runtime) => runtime,
                Err(error) => {
                    let mut process = process;
                    let _ = process.kill();
                    let _ = process.wait();
                    let _ = api.stop_session(&session);
                    self.remove_runtime_config();
                    return Err(error);
                }
            };
            let owner_cidr = room_info
                .members
                .iter()
                .find(|member| member.player_id == room_info.owner_player_id)
                .map(|member| member.assigned_ipv4_cidr.clone())
                .unwrap_or_default();
            let host = match cidr_host(&owner_cidr)
                .or_else(|| cidr_host(&runtime.assigned_ipv4_cidr))
                .or_else(|| cidr_host(&session.assigned_ipv4_cidr))
            {
                Some(host) => host,
                None => {
                    let mut process = process;
                    let _ = process.kill();
                    let _ = process.wait();
                    let _ = api.stop_session(&session);
                    self.remove_runtime_config();
                    return Err(
                        "The Room API did not provide a virtual IPv4 address yet.".to_owned()
                    );
                }
            };
            let state = ConnectionState {
                enabled: true,
                can_connect: true,
                status: "CONNECTED".to_owned(),
                mode: "Room".to_owned(),
                failure_category: "None".to_owned(),
                session_id: session.session_id.clone(),
                room_id: session.room_id.clone(),
                entry_node_url: session.entry_node_url.clone(),
                config_server_url: session.config_server_url.clone(),
                acl_group: session.acl_group.clone(),
                expires_at_epoch_seconds: session.expires_at,
                started_at_ms: Some(now_ms()),
                connected_at_ms: Some(now_ms()),
                last_updated_at_ms: now_ms(),
                last_error_summary: String::new(),
                diagnostics_summary_path: String::new(),
                assigned_ipv4_cidr: if runtime.assigned_ipv4_cidr.is_empty() {
                    session.assigned_ipv4_cidr.clone()
                } else {
                    runtime.assigned_ipv4_cidr.clone()
                },
                current_player_id: self.config.player_id.clone(),
                room_owner_player_id: room_info.owner_player_id,
                room_owner_ipv4_cidr: owner_cidr,
                peer_count: runtime.peer_count,
                relay_server_description: runtime.relay_server_description,
                last_session_state: runtime.session_state,
                last_room_state: runtime.room_state,
                user_initiated: true,
            };
            if let Err(error) = write_state(&self.paths, &state) {
                let mut process = process;
                let _ = process.kill();
                let _ = process.wait();
                let _ = api.stop_session(&session);
                self.remove_runtime_config();
                return Err(error);
            }
            self.active = Some(ActiveConnection {
                session,
                process,
                state,
                next_heartbeat: std::time::Instant::now() + HEARTBEAT_INTERVAL,
            });
            Ok((
                room_id.to_owned(),
                format!("{host}:{TOGETHER_IN_SPIRE_PORT}"),
            ))
        })();
        match result {
            Ok((room_id, address)) => self.emit(WorkerEvent::Connected { room_id, address }),
            Err(error) => {
                self.write_failed_state(&error);
                self.emit(WorkerEvent::Error(error));
            }
        }
    }

    fn heartbeat(&mut self) {
        let Some(mut active) = self.active.take() else {
            return;
        };
        if active.process.try_wait().ok().flatten().is_some() {
            let error = format!(
                "EasyTier stopped unexpectedly. See {}",
                self.paths.log_file().display()
            );
            self.write_failed_state(&error);
            self.remove_runtime_config();
            let _ = self.api().and_then(|api| api.stop_session(&active.session));
            self.emit(WorkerEvent::Error(error));
            return;
        }
        let result = self.api().and_then(|api| {
            api.report_runtime(&active.session, &active.state.relay_server_description)
        });
        match result {
            Ok(runtime) => {
                active.state.peer_count = runtime.peer_count;
                active.state.last_session_state = runtime.session_state;
                active.state.last_room_state = runtime.room_state;
                active.state.relay_server_description = runtime.relay_server_description;
                if !runtime.assigned_ipv4_cidr.is_empty() {
                    active.state.assigned_ipv4_cidr = runtime.assigned_ipv4_cidr;
                }
                active.state.last_updated_at_ms = now_ms();
                if let Err(error) = write_state(&self.paths, &active.state) {
                    self.emit(WorkerEvent::Error(error));
                }
            }
            Err(error) => {
                active.state.last_error_summary = format!("Room update failed: {error}");
                active.state.last_updated_at_ms = now_ms();
                let _ = write_state(&self.paths, &active.state);
            }
        }
        active.next_heartbeat = std::time::Instant::now() + HEARTBEAT_INTERVAL;
        self.active = Some(active);
    }

    fn disconnect(&mut self) {
        let Some(mut active) = self.active.take() else {
            let _ = write_state(&self.paths, &ConnectionState::disconnected(&self.config));
            return;
        };
        let _ = active.process.kill();
        let _ = active.process.wait();
        let _ = self.api().and_then(|api| api.stop_session(&active.session));
        self.remove_runtime_config();
        let _ = write_state(&self.paths, &ConnectionState::disconnected(&self.config));
        self.emit(WorkerEvent::Disconnected);
    }

    fn write_connecting_state(
        &self,
        session: &Session,
        room_info: &RoomInfo,
    ) -> Result<(), String> {
        let owner_cidr = room_info
            .members
            .iter()
            .find(|member| member.player_id == room_info.owner_player_id)
            .map(|member| member.assigned_ipv4_cidr.clone())
            .unwrap_or_default();
        let state = ConnectionState {
            status: "CONNECTING".to_owned(),
            session_id: session.session_id.clone(),
            room_id: session.room_id.clone(),
            entry_node_url: session.entry_node_url.clone(),
            config_server_url: session.config_server_url.clone(),
            acl_group: session.acl_group.clone(),
            expires_at_epoch_seconds: session.expires_at,
            started_at_ms: Some(now_ms()),
            assigned_ipv4_cidr: session.assigned_ipv4_cidr.clone(),
            room_owner_player_id: room_info.owner_player_id.clone(),
            room_owner_ipv4_cidr: owner_cidr,
            ..ConnectionState::disconnected(&self.config)
        };
        write_state(&self.paths, &state)
    }

    fn write_failed_state(&self, error: &str) {
        let state = ConnectionState {
            status: "FAILED".to_owned(),
            failure_category: "RuntimeBridgeUnavailable".to_owned(),
            last_error_summary: error.to_owned(),
            ..ConnectionState::disconnected(&self.config)
        };
        let _ = write_state(&self.paths, &state);
    }

    fn start_easytier(&self, executable: &Path, session: &Session) -> Result<Child, String> {
        let config = easytier_config(session, &self.config.player_id);
        fs::create_dir_all(self.paths.runtime_dir()).map_err(io_error)?;
        write_config(&self.paths.config_file(), &config)?;
        let log = OpenOptions::new()
            .create(true)
            .append(true)
            .open(self.paths.log_file())
            .map_err(io_error)?;
        let mut process = Command::new(executable)
            .arg("-c")
            .arg(self.paths.config_file())
            .current_dir(self.paths.runtime_dir())
            .stdout(Stdio::from(log.try_clone().map_err(io_error)?))
            .stderr(Stdio::from(log))
            .spawn()
            .map_err(|error| format!("Could not start EasyTier: {error}"))?;
        thread::sleep(Duration::from_millis(750));
        if let Some(status) = process.try_wait().map_err(io_error)? {
            return Err(format!(
                "EasyTier exited during startup ({status}). See {}",
                self.paths.log_file().display()
            ));
        }
        Ok(process)
    }

    fn remove_runtime_config(&self) {
        let config = self.paths.config_file();
        let _ = fs::remove_file(&config);
        let _ = fs::remove_file(config.with_extension("toml.bak"));
    }

    fn api(&self) -> Result<RoomApi, String> {
        if !self.config.cloud_control.enabled
            || self
                .config
                .cloud_control
                .room_api_base_url
                .trim()
                .is_empty()
        {
            return Err("EasyTier Room API is unavailable in cloud control.".to_owned());
        }
        RoomApi::new(&self.config.cloud_control.room_api_base_url)
    }

    fn emit(&self, event: WorkerEvent) {
        let _ = self.proxy.send_event(AppEvent::Worker(event));
    }
}

struct TrayMenu {
    status: MenuItem,
    rooms: Submenu,
    refresh: MenuItem,
    create_room: MenuItem,
    disconnect: MenuItem,
    quit: MenuItem,
    room_items: Vec<(MenuItem, String, bool)>,
}

struct RoomSelection {
    room_id: String,
    has_password: bool,
}

struct CreateRoomRequest {
    room_id: String,
    description: String,
    password: String,
}

impl TrayMenu {
    fn new() -> Result<(Self, Menu), Box<dyn std::error::Error>> {
        let menu = Menu::new();
        let status = MenuItem::new("未连接", false, None);
        let rooms = Submenu::new("房间", true);
        let refresh = MenuItem::new("刷新房间", true, None);
        let create_room = MenuItem::new("创建房间", true, None);
        let disconnect = MenuItem::new("断开连接", false, None);
        let quit = MenuItem::new("退出", true, None);
        menu.append(&status)?;
        menu.append(&PredefinedMenuItem::separator())?;
        menu.append(&rooms)?;
        menu.append(&refresh)?;
        menu.append(&create_room)?;
        menu.append(&disconnect)?;
        menu.append(&PredefinedMenuItem::separator())?;
        menu.append(&quit)?;
        Ok((
            Self {
                status,
                rooms,
                refresh,
                create_room,
                disconnect,
                quit,
                room_items: Vec::new(),
            },
            menu,
        ))
    }

    fn set_rooms(&mut self, rooms: Vec<RoomListItem>) {
        for (item, _, _) in self.room_items.drain(..) {
            let _ = self.rooms.remove(&item);
        }
        if rooms.is_empty() {
            let item = MenuItem::new("没有找到可用房间", false, None);
            let _ = self.rooms.append(&item);
            self.room_items.push((item, String::new(), false));
            return;
        }
        for room in rooms {
            let lock = if room.has_password {
                " [需要密码]"
            } else {
                ""
            };
            let detail = if room.description.trim().is_empty() {
                room.owner_display_name
            } else {
                room.description
            };
            let label = format!(
                "{} ({}/{}){} - {}",
                room.room_id, room.online_member_count, room.member_count, lock, detail
            );
            let item = MenuItem::new(label, true, None);
            let _ = self.rooms.append(&item);
            self.room_items
                .push((item, room.room_id, room.has_password));
        }
    }

    fn selected_room(&self, event: &MenuEvent) -> Option<RoomSelection> {
        self.room_items
            .iter()
            .find(|(item, room_id, _)| !room_id.is_empty() && event.id == item.id())
            .map(|(_, room_id, has_password)| RoomSelection {
                room_id: room_id.clone(),
                has_password: *has_password,
            })
    }
}

fn main() {
    let paths = AppPaths::discover();
    if let Err(error) = run(&paths) {
        write_startup_log(&paths, &error.to_string());
        notify(
            "程序启动失败",
            &format!(
                "{}\n\n详细信息：{}",
                error,
                paths.runtime_dir().join("startup.log").display()
            ),
        );
    }
}

fn run(paths: &AppPaths) -> Result<(), Box<dyn std::error::Error>> {
    let _single_instance = match SingleInstance::acquire()? {
        Some(instance) => instance,
        None => {
            notify(
                "程序已在运行",
                "Slay the Amethyst Online 已经在后台运行。\n请从系统托盘使用已有实例。",
            );
            return Ok(());
        }
    };
    let config = match load_config(paths) {
        Ok(config) => config,
        Err(error) => {
            write_startup_log(&paths, &error);
            notify(
                "程序启动失败",
                &format!(
                    "{error}\n\n详细信息：{}",
                    paths.runtime_dir().join("startup.log").display()
                ),
            );
            return Ok(());
        }
    };
    let _ = write_state(&paths, &ConnectionState::disconnected(&config));

    let event_loop: EventLoop<AppEvent> = EventLoop::with_user_event().build()?;
    let proxy = event_loop.create_proxy();
    let (command_sender, command_receiver) = mpsc::channel();
    let worker = Worker {
        paths: paths.clone(),
        config,
        active: None,
        proxy,
    };
    thread::Builder::new()
        .name("sts-easytier-worker".to_owned())
        .spawn(move || worker.run(command_receiver))?;

    let (mut menu, native_menu) = TrayMenu::new()?;
    let _tray = TrayIconBuilder::new()
        .with_menu(Box::new(native_menu))
        .with_tooltip(APP_NAME)
        .with_icon(tray_icon())
        .build()?;
    let _ = command_sender.send(WorkerCommand::Refresh);
    let mut closing = false;

    event_loop.run(move |event, target| {
        target.set_control_flow(ControlFlow::WaitUntil(
            std::time::Instant::now() + Duration::from_millis(250),
        ));
        match event {
            Event::UserEvent(AppEvent::Worker(event)) => match event {
                WorkerEvent::Rooms(rooms) => menu.set_rooms(rooms),
                WorkerEvent::Connecting(room_id) => {
                    let _ = menu.status.set_text(format!("正在连接：{room_id}"));
                    let _ = menu.disconnect.set_enabled(true);
                }
                WorkerEvent::Connected { room_id, address } => {
                    let copied = Clipboard::new()
                        .and_then(|mut clipboard| clipboard.set_text(address.clone()))
                        .is_ok();
                    let _ = menu.status.set_text(format!("已连接：{room_id}"));
                    let _ = menu.disconnect.set_enabled(true);
                    let detail = if copied {
                        format!("已连接到 {room_id}。联机地址 {address} 已复制到剪贴板。")
                    } else {
                        format!("已连接到 {room_id}。联机地址：{address}")
                    };
                    notify("EasyTier 已连接", &detail);
                    let _ = command_sender.send(WorkerCommand::Refresh);
                }
                WorkerEvent::Disconnected => {
                    let _ = menu.status.set_text("未连接");
                    let _ = menu.disconnect.set_enabled(false);
                }
                WorkerEvent::Error(error) => {
                    let _ = menu.status.set_text("连接失败");
                    let _ = menu.disconnect.set_enabled(false);
                    notify("联机连接错误", &error);
                }
                WorkerEvent::ShutdownComplete => target.exit(),
            },
            Event::AboutToWait => {
                while let Ok(event) = MenuEvent::receiver().try_recv() {
                    if event.id == menu.refresh.id() {
                        let _ = command_sender.send(WorkerCommand::Refresh);
                    } else if event.id == menu.disconnect.id() {
                        let _ = command_sender.send(WorkerCommand::Disconnect);
                    } else if event.id == menu.create_room.id() {
                        if let Some(request) = prompt_create_room() {
                            let _ = command_sender.send(WorkerCommand::Connect {
                                room_id: request.room_id,
                                password: request.password,
                                description: request.description,
                                create_only: true,
                            });
                        }
                    } else if event.id == menu.quit.id() {
                        if !closing {
                            closing = true;
                            let _ = command_sender.send(WorkerCommand::Shutdown);
                        }
                    } else if let Some(selection) = menu.selected_room(&event) {
                        let password = if selection.has_password {
                            match prompt_password("加入密码房间", "请输入房间密码：")
                            {
                                Some(password) => password,
                                None => continue,
                            }
                        } else {
                            String::new()
                        };
                        let _ = command_sender.send(WorkerCommand::Connect {
                            room_id: selection.room_id,
                            password,
                            description: String::new(),
                            create_only: false,
                        });
                    }
                }
            }
            _ => {}
        }
    })?;
    Ok(())
}

fn prompt_create_room() -> Option<CreateRoomRequest> {
    let fields = prompt_fields(
        "创建房间",
        &["房间 ID：", "房间描述（可选）：", "房间密码（可选）："],
        &[false, false, true],
    )?;
    let room_id = fields.first()?.clone();
    let room_id = room_id.trim().to_owned();
    if room_id.is_empty() {
        notify(
            "创建房间失败",
            "房间 ID 不能为空。\n房间 ID 只能使用字母、数字、短横线和下划线。",
        );
        return None;
    }
    Some(CreateRoomRequest {
        room_id,
        description: fields.get(1).cloned().unwrap_or_default(),
        password: fields.get(2).cloned().unwrap_or_default(),
    })
}

fn prompt_password(title: &str, message: &str) -> Option<String> {
    prompt_fields(title, &[message], &[true]).and_then(|mut values| values.pop())
}

#[cfg(not(target_os = "windows"))]
fn prompt_fields(_title: &str, _labels: &[&str], _passwords: &[bool]) -> Option<Vec<String>> {
    None
}

#[cfg(target_os = "windows")]
fn prompt_fields(title: &str, labels: &[&str], passwords: &[bool]) -> Option<Vec<String>> {
    if labels.is_empty() || labels.len() != passwords.len() {
        return None;
    }
    let mut state = Box::new(PromptState {
        controls: Vec::new(),
        result: None,
    });
    let state_ptr: *mut PromptState = &mut *state;
    let class_name = wide("SlayTheAmethystPrompt");
    let title = wide(title);
    let instance = unsafe { GetModuleHandleW(std::ptr::null()) };
    if instance.is_null() {
        return None;
    }
    let window_class = WNDCLASSW {
        style: CS_HREDRAW | CS_VREDRAW,
        lpfnWndProc: Some(prompt_window_proc),
        hInstance: instance,
        lpszClassName: class_name.as_ptr(),
        ..unsafe { std::mem::zeroed() }
    };
    unsafe {
        RegisterClassW(&window_class);
    }
    let window = unsafe {
        CreateWindowExW(
            WS_EX_DLGMODALFRAME,
            class_name.as_ptr(),
            title.as_ptr(),
            WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            390,
            150 + labels.len() as i32 * 58,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            instance,
            state_ptr.cast(),
        )
    };
    if window.is_null() {
        return None;
    }
    unsafe {
        for (index, (label, password)) in labels.iter().zip(passwords).enumerate() {
            let label = wide(label);
            CreateWindowExW(
                0,
                wide("STATIC").as_ptr(),
                label.as_ptr(),
                WS_CHILD | WS_VISIBLE,
                20,
                20 + index as i32 * 58,
                340,
                20,
                window,
                std::ptr::null_mut(),
                instance,
                std::ptr::null(),
            );
            let mut style = WS_CHILD | WS_VISIBLE | WS_BORDER | ES_AUTOHSCROLL as u32;
            if *password {
                style |= ES_PASSWORD as u32;
            }
            let edit = CreateWindowExW(
                0,
                wide("EDIT").as_ptr(),
                wide("").as_ptr(),
                style,
                20,
                40 + index as i32 * 58,
                340,
                24,
                window,
                (100 + index) as usize as HMENU,
                instance,
                std::ptr::null(),
            );
            (*state_ptr).controls.push(edit);
        }
        let button_y = 55 + labels.len() as i32 * 58;
        CreateWindowExW(
            0,
            wide("BUTTON").as_ptr(),
            wide("确定").as_ptr(),
            WS_CHILD | WS_VISIBLE | BS_DEFPUSHBUTTON as u32,
            190,
            button_y,
            80,
            28,
            window,
            1 as usize as HMENU,
            instance,
            std::ptr::null(),
        );
        CreateWindowExW(
            0,
            wide("BUTTON").as_ptr(),
            wide("取消").as_ptr(),
            WS_CHILD | WS_VISIBLE,
            280,
            button_y,
            80,
            28,
            window,
            2 as usize as HMENU,
            instance,
            std::ptr::null(),
        );
        ShowWindow(window, SW_SHOW);
        let mut message = MSG::default();
        while GetMessageW(&mut message, std::ptr::null_mut(), 0, 0) > 0 {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    }
    state.result.take()
}

#[cfg(target_os = "windows")]
struct PromptState {
    controls: Vec<HWND>,
    result: Option<Vec<String>>,
}

#[cfg(target_os = "windows")]
unsafe extern "system" fn prompt_window_proc(
    window: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    let state = if message == WM_NCCREATE {
        let create = &*(lparam as *const CREATESTRUCTW);
        let state = create.lpCreateParams as *mut PromptState;
        SetWindowLongPtrW(window, GWLP_USERDATA, state as isize);
        state
    } else {
        GetWindowLongPtrW(window, GWLP_USERDATA) as *mut PromptState
    };
    match message {
        WM_COMMAND if (wparam & 0xffff) == 1 => {
            let mut values = Vec::new();
            for control in &(*state).controls {
                let length = GetWindowTextLengthW(*control) as usize;
                let mut buffer = vec![0_u16; length + 1];
                GetWindowTextW(*control, buffer.as_mut_ptr(), buffer.len() as i32);
                values.push(String::from_utf16_lossy(&buffer[..length]));
            }
            (*state).result = Some(values);
            DestroyWindow(window);
            0
        }
        WM_COMMAND if (wparam & 0xffff) == 2 => {
            DestroyWindow(window);
            0
        }
        WM_CLOSE => {
            DestroyWindow(window);
            0
        }
        WM_DESTROY => {
            PostQuitMessage(0);
            0
        }
        _ => DefWindowProcW(window, message, wparam, lparam),
    }
}

#[cfg(target_os = "windows")]
fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(std::iter::once(0)).collect()
}

fn write_state(paths: &AppPaths, state: &ConnectionState) -> Result<(), String> {
    write_json(&paths.state_file(), state, true)
}

fn write_config(path: &Path, contents: &str) -> Result<(), String> {
    let parent = path
        .parent()
        .ok_or_else(|| "EasyTier config has no parent directory.".to_owned())?;
    fs::create_dir_all(parent).map_err(io_error)?;
    fs::write(path, contents).map_err(io_error)
}

fn write_json<T: Serialize>(path: &Path, value: &T, backup: bool) -> Result<(), String> {
    let parent = path
        .parent()
        .ok_or_else(|| "Output file has no parent directory.".to_owned())?;
    fs::create_dir_all(parent).map_err(io_error)?;
    let contents = serde_json::to_vec_pretty(value).map_err(|error| error.to_string())?;
    let temporary = parent.join(format!(
        ".{}.{}.tmp",
        path.file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("state"),
        now_ms()
    ));
    let mut file = File::create(&temporary).map_err(io_error)?;
    file.write_all(&contents).map_err(io_error)?;
    file.sync_all().map_err(io_error)?;
    drop(file);
    if backup && path.is_file() {
        let backup = path.with_file_name(format!(
            "{}.bak",
            path.file_name()
                .and_then(|name| name.to_str())
                .unwrap_or("state")
        ));
        let _ = fs::copy(path, backup);
    }
    match fs::rename(&temporary, path) {
        Ok(()) => Ok(()),
        Err(rename_error) if path.is_file() => {
            fs::remove_file(path).map_err(io_error)?;
            fs::rename(&temporary, path).map_err(|_| io_error(rename_error))
        }
        Err(error) => Err(io_error(error)),
    }
}

fn easytier_config(session: &Session, player_id: &str) -> String {
    let assigned = valid_cidr(&session.assigned_ipv4_cidr);
    let mut config = format!(
        "instance_name = {}\nhostname = {}\n",
        toml_string(&stable_name(
            "sts-pc",
            &session.session_id,
            "session",
            96,
            12
        )),
        toml_string(&stable_name("sts", player_id, "player", 63, 8)),
    );
    if let Some(cidr) = assigned {
        config.push_str(&format!("ipv4 = {}\ndhcp = false\n", toml_string(cidr)));
    } else {
        config.push_str("dhcp = true\n");
    }
    config.push_str(&format!(
        "listeners = []\n\n[network_identity]\nnetwork_name = {}\nnetwork_secret = {}\n\n[[peer]]\nuri = {}\n",
        toml_string(&stable_name("sts", &session.room_id, "default-room", 96, 12)),
        toml_string(&session.network_secret),
        toml_string(&session.entry_node_url),
    ));
    config
}

fn stable_name(
    prefix: &str,
    value: &str,
    fallback: &str,
    max_length: usize,
    hash_length: usize,
) -> String {
    let input = if value.trim().is_empty() {
        fallback
    } else {
        value.trim()
    };
    let hash = hex_sha256(input)[..hash_length].to_owned();
    let body: String = value
        .trim()
        .to_ascii_lowercase()
        .chars()
        .map(|character| {
            if character.is_ascii_lowercase() || character.is_ascii_digit() {
                character
            } else {
                '-'
            }
        })
        .collect::<String>()
        .split('-')
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>()
        .join("-");
    let body = if body.is_empty() {
        fallback.to_owned()
    } else {
        body
    };
    let available = max_length
        .saturating_sub(prefix.len() + hash.len() + 2)
        .max(1);
    let body = body
        .chars()
        .take(available)
        .collect::<String>()
        .trim_end_matches('-')
        .to_owned();
    format!(
        "{prefix}-{}-{hash}",
        if body.is_empty() { fallback } else { &body }
    )
}

fn stable_mac_address(player_id: &str) -> String {
    let mut bytes = Sha256::digest(player_id.trim().as_bytes())[0..6].to_vec();
    bytes[0] = (bytes[0] & 0xfe) | 0x02;
    bytes
        .iter()
        .map(|byte| format!("{byte:02X}"))
        .collect::<Vec<_>>()
        .join(":")
}

fn hex_sha256(value: &str) -> String {
    hex_sha256_bytes(value.as_bytes())
}

fn hex_sha256_bytes(value: &[u8]) -> String {
    Sha256::digest(value)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

fn toml_string(value: &str) -> String {
    format!(
        "\"{}\"",
        value
            .replace('\\', "\\\\")
            .replace('"', "\\\"")
            .replace('\n', "\\n")
            .replace('\r', "\\r")
            .replace('\t', "\\t")
    )
}

fn valid_cidr(value: &str) -> Option<&str> {
    let (address, prefix) = value.trim().split_once('/')?;
    let prefix = prefix.parse::<u8>().ok()?;
    if prefix > 32 || cidr_host(address).is_none() {
        None
    } else {
        Some(value.trim())
    }
}

fn cidr_host(value: &str) -> Option<String> {
    let host = value.trim().split('/').next()?.trim();
    let octets = host.split('.').collect::<Vec<_>>();
    if octets.len() == 4 && octets.iter().all(|octet| octet.parse::<u8>().is_ok()) {
        Some(host.to_owned())
    } else {
        None
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn io_error(error: std::io::Error) -> String {
    error.to_string()
}

fn notify(title: &str, description: &str) {
    let _ = rfd::MessageDialog::new()
        .set_title(title)
        .set_description(description)
        .set_level(rfd::MessageLevel::Info)
        .set_buttons(rfd::MessageButtons::Ok)
        .show();
}

fn tray_icon() -> Icon {
    let image = image::load_from_memory(EMBEDDED_APP_ICON)
        .expect("embedded app icon must be valid PNG")
        .resize_exact(32, 32, image::imageops::FilterType::Lanczos3)
        .to_rgba8();
    Icon::from_rgba(image.into_raw(), 32, 32).expect("embedded tray icon must be valid")
}
