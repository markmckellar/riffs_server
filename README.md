# riffs_wf_server

Server for a two-player riff guessing game **Riff Rivals** (name notes: `research/naming.md` in the goals
repo, `mm_home/goals/riff_goals/`). Spring Boot 2.7 / Java 17, data in JSON files for now (`local/data/`).
The web client is `../riffs_wf_client_vue`.

## Run it locally
```bash
./run_dev.bash            # port 9095, name-only sign-in (no Google), data in local/data/
mvn test                  # heuristic, tune library and game rule tests
```
Then start the client (`cd ../riffs_wf_client_vue && npm run dev`) and open http://localhost:5175/.

## Settings (`application.properties`, or `--name=value`)
| Setting | Default | |
|---|---|---|
| `riffs.data-dir` | `local/data` | users.json, games.json |
| `riffs.auth.google-client-id` | empty | OAuth web client ID; empty = Google sign-in off |
| `riffs.auth.dev-login` | false | sign in by typing a name. **Testing only** |
| `riffs.auth.session-key-file` | `local/session.key` | signs the sign-in cookie; made on first start |
| `riffs.bot.min-delay` / `max-delay` | 3 / 8 | seconds a bot "thinks" |

**Google sign-in:** same approach as cmo_server (Google Identity Services button → `POST /api/auth/google` →
token checked with Google → signed HttpOnly cookie). It needs an OAuth **web client ID** with the site's
origin under "Authorized JavaScript origins". Either add the game's origin to the cmo "Home Cams web" client,
or (better, so the consent screen shows the game's name) make a new client in the same Google Cloud project.

## API (all JSON; everything under /api except auth and health needs the sign-in cookie)
| Method | Path | |
|---|---|---|
| GET | `/api/health` | |
| GET | `/api/auth/config` | game name, Google client id, dev-login on?, who is signed in |
| POST | `/api/auth/google` `{credential}` · `/api/auth/dev` `{name}` · `/api/auth/logout` | |
| GET | `/api/players` | everyone (people and bots), online flag, wins/losses |
| GET | `/api/games` | my games, active first |
| POST | `/api/games` `{opponentId?}` | start a game; no opponent = quick match (someone online, else a bot) |
| GET | `/api/games/{id}` | the game as I may see it |
| POST | `/api/games/{id}/riff` `{notes:[{m,b}], tuneId?}` | send my riff (m = MIDI pitch or null for a rest, b = beats) |
| POST | `/api/games/{id}/guess` `{notes:[midi…]}` | guess; each note is marked exact / octave / close (+up/down) / miss |
| POST | `/api/games/{id}/hint` · `/extra-string` · `/resign` | spend a token, or give up |
| POST | `/api/riffs/evaluate` `{notes}` | the "makes musical sense" score with reasons (nothing saved) |
| GET | `/api/riffs/rules` | note limits, pitch range, strings, encores |
| GET | `/api/library` · `/api/library/{tuneId}/fragments` | public-domain tunes and their riff-sized pieces |
| GET | `/api/events?after=seq` | what happened since `seq` (the client polls every 3 s) |

## Code map
- `music/` `Note`, `RiffRules` (4–8 notes, ≤ 2 rests, G3–C6), `MusicSense` (the heuristic)
- `library/TuneLibrary` + `resources/tunes.json`: tunes cut into riffs at phrase boundaries
- `game/` `Game` (stored model), `GameService` (rules, rewards, views), `GameRules` (numbers), `BotPlayer`
- `auth/` Google sign-in and session cookie (adapted from cmo_server), `user/`, `events/`, `store/JsonFiles`

## Known limits (first version)
- The listener's phone gets the riff's notes so it can play them, so a player could cheat with dev tools.
- Events are polled and kept in memory; a phone isn't told anything while the page is closed (Web Push is a goal).
