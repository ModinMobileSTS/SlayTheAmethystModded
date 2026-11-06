use crate::peer_status::MemberView;
use std::sync::{Arc, Mutex};

pub type SharedMembers = Arc<Mutex<MemberView>>;

#[cfg(not(target_os = "windows"))]
pub fn show(_members: SharedMembers) {}

#[cfg(target_os = "windows")]
pub fn show(members: SharedMembers) {
    windows::show(members);
}

#[cfg(target_os = "windows")]
mod windows {
    use super::*;
    use crate::peer_status::LinkState;
    use windows_sys::Win32::{
        Foundation::{HWND, LPARAM, LRESULT, RECT, WPARAM},
        Graphics::Gdi::*,
        System::LibraryLoader::GetModuleHandleW,
        UI::WindowsAndMessaging::*,
    };

    struct State {
        members: SharedMembers,
        scroll: i32,
        owned_by_window: bool,
    }

    fn rgb(r: u32, g: u32, b: u32) -> u32 {
        r | (g << 8) | (b << 16)
    }

    pub fn show(members: SharedMembers) {
        unsafe {
            let class = crate::wide("SlayTheAmethystMembers");
            let existing = FindWindowW(class.as_ptr(), std::ptr::null());
            if !existing.is_null() {
                ShowWindow(existing, SW_SHOW);
                SetForegroundWindow(existing);
                return;
            }
            let instance = GetModuleHandleW(std::ptr::null());
            let wc = WNDCLASSW {
                lpfnWndProc: Some(window_proc),
                hInstance: instance,
                lpszClassName: class.as_ptr(),
                hCursor: LoadCursorW(std::ptr::null_mut(), IDC_ARROW),
                ..std::mem::zeroed()
            };
            RegisterClassW(&wc);
            let state = Box::into_raw(Box::new(State {
                members,
                scroll: 0,
                owned_by_window: false,
            }));
            let window = CreateWindowExW(
                0,
                class.as_ptr(),
                crate::wide("房间成员 · 连接状态").as_ptr(),
                WS_OVERLAPPEDWINDOW,
                CW_USEDEFAULT,
                CW_USEDEFAULT,
                620,
                560,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                instance,
                state.cast(),
            );
            if window.is_null() {
                drop(Box::from_raw(state));
                return;
            }
            (*state).owned_by_window = true;
            SetTimer(window, 1, 1000, None);
            ShowWindow(window, SW_SHOW);
        }
    }

    unsafe extern "system" fn window_proc(hwnd: HWND, msg: u32, wp: WPARAM, lp: LPARAM) -> LRESULT {
        let state = if msg == WM_NCCREATE {
            let create = &*(lp as *const CREATESTRUCTW);
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, create.lpCreateParams as isize);
            create.lpCreateParams as *mut State
        } else {
            GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *mut State
        };
        if state.is_null() {
            return DefWindowProcW(hwnd, msg, wp, lp);
        }
        match msg {
            WM_PAINT => {
                paint(hwnd, &mut *state);
                0
            }
            WM_TIMER | WM_SIZE => {
                InvalidateRect(hwnd, std::ptr::null(), 0);
                0
            }
            WM_MOUSEWHEEL => {
                let delta = ((wp >> 16) as u16 as i16) as i32;
                (*state).scroll = ((*state).scroll - delta / 2).max(0);
                InvalidateRect(hwnd, std::ptr::null(), 0);
                0
            }
            WM_CLOSE => {
                ShowWindow(hwnd, SW_HIDE);
                0
            }
            WM_ERASEBKGND => 1,
            WM_NCDESTROY => {
                KillTimer(hwnd, 1);
                SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
                if (*state).owned_by_window {
                    drop(Box::from_raw(state));
                }
                DefWindowProcW(hwnd, msg, wp, lp)
            }
            _ => DefWindowProcW(hwnd, msg, wp, lp),
        }
    }

    unsafe fn text(dc: HDC, text: &str, rect: &mut RECT, color: u32, flags: u32) {
        SetTextColor(dc, color);
        let wide = crate::wide(text);
        DrawTextW(dc, wide.as_ptr(), (wide.len() - 1) as i32, rect, flags);
    }

    unsafe fn pill(dc: HDC, rect: RECT, color: u32) {
        let brush = CreateSolidBrush(color);
        let old_brush = SelectObject(dc, brush);
        let old_pen = SelectObject(dc, GetStockObject(NULL_PEN));
        RoundRect(dc, rect.left, rect.top, rect.right, rect.bottom, 28, 28);
        SelectObject(dc, old_brush);
        SelectObject(dc, old_pen);
        DeleteObject(brush);
    }

    unsafe fn paint(hwnd: HWND, state: &mut State) {
        let mut ps: PAINTSTRUCT = std::mem::zeroed();
        let dc = BeginPaint(hwnd, &mut ps);
        let mut client: RECT = std::mem::zeroed();
        GetClientRect(hwnd, &mut client);
        let background = CreateSolidBrush(rgb(246, 245, 250));
        FillRect(dc, &client, background);
        DeleteObject(background);
        SetBkMode(dc, TRANSPARENT as i32);
        let font = CreateFontW(
            -17,
            0,
            0,
            0,
            400,
            0,
            0,
            0,
            DEFAULT_CHARSET as u32,
            0,
            0,
            CLEARTYPE_QUALITY as u32,
            0,
            crate::wide("Microsoft YaHei UI").as_ptr(),
        );
        let old_font = SelectObject(dc, font);
        let view = state
            .members
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        let width = client.right.max(320);
        text(
            dc,
            &if view.room_id.is_empty() {
                "未连接房间".into()
            } else {
                format!("房间：{}", view.room_id)
            },
            &mut RECT {
                left: 22,
                top: 18,
                right: width - 20,
                bottom: 46,
            },
            rgb(40, 32, 55),
            DT_SINGLELINE | DT_END_ELLIPSIS,
        );
        text(
            dc,
            "显示你到各玩家的连接方式 · 每 5 秒更新 · 滚轮浏览",
            &mut RECT {
                left: 22,
                top: 48,
                right: width - 20,
                bottom: 76,
            },
            rgb(105, 98, 116),
            DT_SINGLELINE | DT_END_ELLIPSIS,
        );
        let count = view.members.len() as i32;
        state.scroll = state.scroll.min((count * 84 - (client.bottom - 88)).max(0));
        IntersectClipRect(dc, 0, 84, width, client.bottom);
        if count == 0 {
            text(
                dc,
                "加入房间后，这里将显示成员及直连 / 中转状态。",
                &mut RECT {
                    left: 22,
                    top: 100,
                    right: width - 20,
                    bottom: 150,
                },
                rgb(105, 98, 116),
                DT_WORDBREAK,
            );
        }
        for (i, member) in view.members.iter().enumerate() {
            let y = 90 + i as i32 * 84 - state.scroll;
            if y + 76 < 84 || y > client.bottom {
                continue;
            }
            pill(
                dc,
                RECT {
                    left: 14,
                    top: y,
                    right: width - 14,
                    bottom: y + 76,
                },
                rgb(255, 255, 255),
            );
            let (kind, label) = view.link(member, crate::now_ms());
            let (bg, fg) = match kind {
                LinkState::Direct => (rgb(219, 239, 228), rgb(24, 101, 67)),
                LinkState::Relay => (rgb(239, 225, 251), rgb(111, 56, 148)),
                LinkState::Connecting => (rgb(225, 234, 253), rgb(53, 85, 142)),
                _ => (rgb(237, 235, 241), rgb(100, 94, 111)),
            };
            let mut measured: RECT = std::mem::zeroed();
            text(dc, &label, &mut measured, fg, DT_CALCRECT | DT_SINGLELINE);
            let pill_width = (measured.right + 24).min(width / 2);
            let x = width - 28 - pill_width;
            pill(
                dc,
                RECT {
                    left: x,
                    top: y + 12,
                    right: width - 28,
                    bottom: y + 42,
                },
                bg,
            );
            text(
                dc,
                &label,
                &mut RECT {
                    left: x + 10,
                    top: y + 12,
                    right: width - 34,
                    bottom: y + 42,
                },
                fg,
                DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS,
            );
            let name = if member.display_name.is_empty() {
                &member.player_id
            } else {
                &member.display_name
            };
            text(
                dc,
                name,
                &mut RECT {
                    left: 28,
                    top: y + 12,
                    right: x - 8,
                    bottom: y + 38,
                },
                rgb(40, 32, 55),
                DT_SINGLELINE | DT_END_ELLIPSIS,
            );
            text(
                dc,
                &member.assigned_ipv4_cidr,
                &mut RECT {
                    left: 28,
                    top: y + 44,
                    right: width - 28,
                    bottom: y + 68,
                },
                rgb(105, 98, 116),
                DT_SINGLELINE | DT_END_ELLIPSIS,
            );
        }
        SelectObject(dc, old_font);
        DeleteObject(font);
        EndPaint(hwnd, &ps);
    }
}
