use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub enum ConnectionKind {
    Direct,
    Relay,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PeerConnection {
    pub virtual_ipv4: String,
    pub kind: ConnectionKind,
    pub protocol: String,
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoomMember {
    pub player_id: String,
    #[serde(default)]
    pub display_name: String,
    #[serde(default)]
    pub online: bool,
    #[serde(default)]
    pub assigned_ipv4_cidr: String,
}

#[derive(Clone, Default)]
pub struct MemberView {
    pub room_id: String,
    pub current_player_id: String,
    pub members: Vec<RoomMember>,
    pub connections: Vec<PeerConnection>,
    pub valid_until_ms: u64,
}

#[derive(Debug, PartialEq, Eq)]
pub enum LinkState {
    SelfPlayer,
    Direct,
    Relay,
    Connecting,
    Unknown,
    Offline,
}

impl MemberView {
    pub fn link(&self, member: &RoomMember, now_ms: u64) -> (LinkState, String) {
        if member.player_id == self.current_player_id {
            return (LinkState::SelfPlayer, "本机".to_owned());
        }
        if !member.online {
            return (LinkState::Offline, "离线".to_owned());
        }
        if self.valid_until_ms <= now_ms {
            return (LinkState::Unknown, "未知".to_owned());
        }
        let host = host(&member.assigned_ipv4_cidr);
        let Some(conn) = self
            .connections
            .iter()
            .find(|c| Some(&c.virtual_ipv4) == host.as_ref())
        else {
            return (LinkState::Connecting, "连接中".to_owned());
        };
        match conn.kind {
            ConnectionKind::Direct => (
                LinkState::Direct,
                if conn.protocol.is_empty() {
                    "直连".to_owned()
                } else {
                    format!("直连 · {}", conn.protocol.to_ascii_uppercase())
                },
            ),
            ConnectionKind::Relay => (LinkState::Relay, "中转".to_owned()),
        }
    }
}

pub fn host(cidr: &str) -> Option<String> {
    cidr.trim()
        .split('/')
        .next()?
        .parse::<std::net::Ipv4Addr>()
        .ok()
        .map(|ip| ip.to_string())
}

fn field<'a>(value: &'a Value, snake: &str, camel: &str) -> &'a Value {
    value
        .get(snake)
        .or_else(|| value.get(camel))
        .unwrap_or(&Value::Null)
}

fn number(value: &Value) -> Option<u64> {
    value.as_u64().or_else(|| value.as_str()?.parse().ok())
}

fn ipv4(value: &Value) -> Option<String> {
    if let Some(text) = value.as_str() {
        return host(text);
    }
    let addr = number(&value["address"]["addr"])?;
    Some(std::net::Ipv4Addr::from(u32::try_from(addr).ok()?).to_string())
}

/// `easytier-cli -v -o json -n <instance> peer` returns raw PeerRoutePair[].
/// Do not parse the human-readable table or use the bootstrap server as the member.
pub fn parse_peer_routes(json: &str, local_cidr: &str) -> Result<Vec<PeerConnection>, String> {
    let root: Value = serde_json::from_str(json).map_err(|e| e.to_string())?;
    let pairs = root.as_array().ok_or("Expected peer route array")?;
    let mut result = Vec::new();
    for pair in pairs {
        let route = &pair["route"];
        let flags = field(route, "feature_flag", "featureFlag");
        if field(flags, "is_public_server", "isPublicServer").as_bool() == Some(true) {
            continue;
        }
        let Some(ip) = ipv4(field(route, "ipv4_addr", "ipv4Addr")) else {
            continue;
        };
        if Some(&ip) == host(local_cidr).as_ref() || ip == "0.0.0.0" {
            continue;
        }
        let conns: Vec<_> = pair["peer"]["conns"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|conn| field(conn, "is_closed", "isClosed").as_bool() != Some(true))
            .collect();
        let kind = match number(&route["cost"]) {
            Some(1) if !conns.is_empty() => ConnectionKind::Direct,
            Some(cost) if cost > 1 => ConnectionKind::Relay,
            _ => continue,
        };
        let mut protocols = Vec::new();
        if kind == ConnectionKind::Direct {
            for conn in conns {
                let protocol = field(&conn["tunnel"], "tunnel_type", "tunnelType")
                    .as_str()
                    .unwrap_or("")
                    .to_ascii_lowercase();
                if ["udp", "tcp", "quic", "ws", "wss", "wg"].contains(&protocol.as_str()) {
                    protocols.push(protocol);
                }
            }
        }
        protocols.sort();
        protocols.dedup();
        if !result.iter().any(|c: &PeerConnection| c.virtual_ipv4 == ip) {
            result.push(PeerConnection {
                virtual_ipv4: ip,
                kind,
                protocol: protocols.join("/"),
            });
        }
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_raw_cli_routes_not_proxy_subnets_or_servers() {
        let json = r#"[
          {"route":{"ipv4_addr":{"address":{"addr":167772162}},"cost":1},"peer":{"conns":[{"tunnel":{"tunnel_type":"udp"}}]}},
          {"route":{"ipv4Addr":"10.0.0.3/24","cost":2},"peer":{"conns":[{"tunnel":{"tunnelType":"tcp"}}]}},
          {"route":{"ipv4_addr":"10.0.0.4/24","cost":1},"peer":{"conns":[{"is_closed":true}]}},
          {"route":{"ipv4_addr":"10.0.0.5/24","cost":1,"feature_flag":{"is_public_server":true}},"peer":{"conns":[{}]}},
          {"route":{"ipv4_addr":"10.0.0.1/24","cost":1},"peer":{"conns":[{}]}},
          {"route":{"proxy_cidrs":["10.0.0.7/32"],"cost":2}},
          {"route":{"ipv4_addr":"10.0.0.8/24","cost":0}}
        ]"#;
        let routes = parse_peer_routes(json, "10.0.0.1/24").unwrap();
        assert_eq!(
            routes,
            vec![
                PeerConnection {
                    virtual_ipv4: "10.0.0.2".into(),
                    kind: ConnectionKind::Direct,
                    protocol: "udp".into()
                },
                PeerConnection {
                    virtual_ipv4: "10.0.0.3".into(),
                    kind: ConnectionKind::Relay,
                    protocol: "".into()
                },
            ]
        );
        assert!(parse_peer_routes("{}", "").is_err());
        assert!(parse_peer_routes("invalid", "").is_err());
        assert!(parse_peer_routes("[]", "").unwrap().is_empty());
    }

    #[test]
    fn member_mapping_handles_stale_offline_self_missing_and_relay() {
        let member = RoomMember {
            player_id: "other".into(),
            display_name: "Player".into(),
            online: true,
            assigned_ipv4_cidr: "10.0.0.2/32".into(),
        };
        let mut view = MemberView {
            current_player_id: "me".into(),
            valid_until_ms: 200,
            connections: vec![PeerConnection {
                virtual_ipv4: "10.0.0.2".into(),
                kind: ConnectionKind::Direct,
                protocol: "udp".into(),
            }],
            ..Default::default()
        };
        assert_eq!(
            view.link(&member, 100),
            (LinkState::Direct, "直连 · UDP".into())
        );
        assert_eq!(view.link(&member, 200).0, LinkState::Unknown);
        assert_eq!(
            view.link(
                &RoomMember {
                    online: false,
                    ..member.clone()
                },
                100
            )
            .0,
            LinkState::Offline
        );
        assert_eq!(
            view.link(
                &RoomMember {
                    player_id: "me".into(),
                    ..member.clone()
                },
                100
            )
            .0,
            LinkState::SelfPlayer
        );
        view.connections[0].kind = ConnectionKind::Relay;
        assert_eq!(view.link(&member, 100).0, LinkState::Relay);
        view.connections.clear();
        assert_eq!(view.link(&member, 100).0, LinkState::Connecting);
        view.valid_until_ms = 0;
        assert_eq!(view.link(&member, 100).0, LinkState::Unknown);
    }
}
