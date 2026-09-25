<div align=center>
    <h1>Stolia Proxy</h1>
    <p>A <a href="https://github.com/PaperMC/Velocity">Velocity</a> fork for the <a href="https://github.com/AKSHRK-Dev/Stolia">Stolia</a> server family,
    with 50 built-in placeholders for the MOTD, tab list and messages, and a placeholder API for plugins.</p>
</div>

## Features

- Everything Velocity does (all Velocity plugins work).
- **50 placeholders** (`{online}`, `{player_ping}`, `{status_lobby}`, ...) usable anywhere Stolia Proxy shows text,
  with [MiniMessage](https://docs.advntr.dev/minimessage/format) formatting.
- **Server list MOTD**, **tab list header/footer** (refreshed every second) and **join / leave / server switch
  messages**, all configured in `stolia-proxy.toml`.
- Backend servers are pinged in the background, so `{status_<server>}`, `{motd_<server>}` and friends never
  slow anything down.
- `/stoliaproxy placeholders` lists every placeholder with its current value, `/stoliaproxy parse <text>` previews a
  line, `/stoliaproxy reload` reloads the config (permission `stoliaproxy.admin`).

## Configuration (`stolia-proxy.toml`)

Created on first start:

```toml
[motd]
enabled = true
lines = [
  "<gradient:#bdbdbd:#ffffff><bold>{proxy_name}</bold></gradient> <dark_gray>|</dark_gray> <gray>{servers_online}/{server_count} servers up",
  "<gray>{online} players online <dark_gray>·</dark_gray> {time}"
]
max-players = -1

[tablist]
enabled = true
interval-ms = 1000
header = ["", "<white><bold>{proxy_name}</bold>", "<gray>{online}/{max} online <dark_gray>·</dark_gray> {time}", ""]
footer = ["", "<gray>{player_server} · ping <{player_ping_color}>{player_ping}ms</{player_ping_color}>", "<dark_gray>uptime {uptime}", ""]

[messages]
join = "<dark_gray>[<green>+</green>]</dark_gray> <gray>{player}"
leave = "<dark_gray>[<red>-</red>]</dark_gray> <gray>{player}"
switch = "<dark_gray>[<aqua>»</aqua>]</dark_gray> <gray>{player}: {previous_server} → {player_server}"

[server-status]
refresh-seconds = 10
```

## Placeholders

### Proxy
| Placeholder | Value |
| --- | --- |
| `{proxy_name}` | Proxy name |
| `{proxy_version}` | Proxy version |
| `{online}` | Players on the proxy |
| `{max}` | Max players (show-max-players) |
| `{server_count}` | Registered backend servers |
| `{servers_online}` | Backend servers answering pings |
| `{servers}` | Backend server names |
| `{most_popular_server}` | Backend with the most players |
| `{time}` | HH:mm |
| `{time_seconds}` | HH:mm:ss |
| `{date}` | yyyy-MM-dd |
| `{datetime}` | yyyy-MM-dd HH:mm |
| `{day_of_week}` | Day of the week (viewer's language) |
| `{uptime}` | Proxy uptime, e.g. 2d 3h 4m |
| `{uptime_seconds}` | Proxy uptime in seconds |
| `{memory_used}` | Heap used (MB) |
| `{memory_max}` | Max heap (MB) |
| `{memory_free}` | Free heap (MB) |
| `{memory_percent}` | Heap used (%) |
| `{cpu_cores}` | CPU cores |
| `{cpu_load}` | Proxy process CPU (%) |
| `{java_version}` | Java version |
| `{os}` | Operating system |
| `{plugin_count}` | Loaded plugins |

### Backend servers (`<server>` is the name in `velocity.toml`, e.g. `{online_lobby}`)
| Placeholder | Value |
| --- | --- |
| `{online_<server>}` | Players on the server (via this proxy) |
| `{players_<server>}` | Names of players on the server |
| `{status_<server>}` | online / offline |
| `{status_color_<server>}` | green / red, for <{status_color_lobby}> |
| `{max_<server>}` | Max players it reports |
| `{motd_<server>}` | Its MOTD as plain text |
| `{version_<server>}` | Its version name |
| `{latency_<server>}` | Ping from the proxy (ms) |
| `{address_<server>}` | host:port |

### Viewing player (empty in the server list MOTD, where there is no player)
| Placeholder | Value |
| --- | --- |
| `{player}` | Name |
| `{player_uuid}` | UUID |
| `{player_ping}` | Ping (ms) |
| `{player_ping_color}` | green / yellow / red by ping |
| `{player_server}` | Current server |
| `{player_server_online}` | Players on that server |
| `{player_server_max}` | Max players of that server |
| `{player_server_motd}` | MOTD of that server |
| `{player_locale}` | Locale, e.g. ja_jp |
| `{player_language}` | Language name |
| `{player_client_brand}` | vanilla, fabric, ... |
| `{player_protocol}` | Protocol number |
| `{player_version}` | Client Minecraft version |
| `{player_virtual_host}` | Host name used to connect |
| `{player_online_mode}` | true if the account is verified |
| `{player_session}` | Time since joining |
| `{player_session_minutes}` | Minutes since joining |

Messages also have `{previous_server}` for server switches. Unknown placeholders are left as they are.

## Placeholder API for plugins

Depend on the Stolia Proxy API (the Velocity API plus `dev.stolia.proxy.api`):

```java
StoliaPlaceholders placeholders = StoliaPlaceholders.get();
placeholders.register("coins", player -> player == null ? "0" : String.valueOf(coins(player)));
placeholders.registerPrefix("team_", (player, team) -> String.valueOf(teamSize(team)));   // {team_red}
Component line = placeholders.render("<gold>{coins} coins</gold> on {player_server}", player);
```

Registered placeholders work in `stolia-proxy.toml` too.

## Building

```sh
./gradlew :velocity-proxy:shadowJar
# -> proxy/build/libs/stolia-proxy-<version>.jar
```

## License

Stolia Proxy is a fork of [Velocity](https://github.com/PaperMC/Velocity) and keeps its licenses: the proxy is
GPLv3 and the API is MIT (see `LICENSE` and `api/LICENSE`). The original README is in
[VELOCITY_README.md](./VELOCITY_README.md).

